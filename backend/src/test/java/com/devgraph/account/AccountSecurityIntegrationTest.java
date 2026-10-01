package com.devgraph.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.account.application.AccountPurgeService;
import com.devgraph.auth.application.AdminAccountService;
import com.devgraph.auth.application.ReauthPurpose;
import com.devgraph.auth.application.ReauthService;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.ops.RetentionService;
import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * 설계서 §19 Phase 7 테스트(보안): 비밀번호 변경, 제한 세션(§17.2.3), 재인증(§17.2.2), 영구 삭제, 계정 탈퇴·purge,
 * 보존 배치(§21.5), 보안 헤더(§17.7), 감사 로그에 비밀이 남지 않는 것.
 */
class AccountSecurityIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AdminAccountService adminAccountService;
	@Autowired
	ReauthService reauthService;
	@Autowired
	AccountPurgeService purgeService;
	@Autowired
	RetentionService retentionService;

	/** 로그인한 한 기기. */
	record Device(String email, String accessToken, String refreshCookie, String csrfCookie) {

		UUID userId() {
			return UUID.fromString(claim(accessToken, "sub"));
		}

		UUID familyId() {
			return UUID.fromString(claim(accessToken, "sid"));
		}

		String restriction() {
			return claim(accessToken, "restriction");
		}
	}

	// ---- password change (AUTH-05) ---------------------------------------------------------

	@Test
	void passwordChangeRequiresTheCurrentPasswordAndCanRevokeOtherSessions() {
		String email = signupEmail();
		Device first = signup(email, PASSWORD);
		Device second = login(email, PASSWORD);

		var wrong = call(HttpMethod.PATCH, "/api/v1/auth/password", first,
				Map.of("currentPassword", "nope-nope-nope", "newPassword", "brand-new-passw0rd"));
		assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrong.getBody()).contains("INVALID_CREDENTIALS");
		assertThat(call(HttpMethod.PATCH, "/api/v1/auth/password", first,
				Map.of("currentPassword", PASSWORD, "newPassword", "short")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		var same = call(HttpMethod.PATCH, "/api/v1/auth/password", first,
				Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD));
		assertThat(same.getBody()).contains("SAME_AS_CURRENT");

		var changed = call(HttpMethod.PATCH, "/api/v1/auth/password", first,
				Map.of("currentPassword", PASSWORD, "newPassword", "brand-new-passw0rd", "revokeOtherSessions", true));
		assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		// 이전 비밀번호로는 로그인할 수 없고 새 비밀번호로는 된다.
		assertThat(loginResponse(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(loginResponse(email, "brand-new-passw0rd").getStatusCode()).isEqualTo(HttpStatus.OK);
		// 다른 기기의 세션은 폐기되고 현재 기기는 유지된다.
		assertThat(refresh(second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(first).getStatusCode()).isEqualTo(HttpStatus.OK);
		// 감사 로그에는 이유와 건수만 있고 비밀번호는 없다.
		assertThat(auditMetadata(first.userId(), "AUTH_PASSWORD_CHANGE")).noneMatch(m -> m.contains("passw0rd") || m.contains(PASSWORD));
	}

	@Test
	void passwordChangeWithoutRevokingKeepsOtherSessions() {
		String email = signupEmail();
		Device first = signup(email, PASSWORD);
		Device second = login(email, PASSWORD);
		call(HttpMethod.PATCH, "/api/v1/auth/password", first, Map.of("currentPassword", PASSWORD, "newPassword", "another-passw0rd"));
		assertThat(refresh(second).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ---- restriction (17.2.3) + admin CLI service (17.8) ---------------------------------------

	@Test
	void forcedPasswordResetRestrictsTheSessionUntilThePasswordIsChanged() {
		String email = signupEmail();
		Device old = signup(email, PASSWORD);

		String temporary = adminAccountService.forcePasswordReset(email);
		assertThat(temporary).hasSize(20);
		// 기존 세션은 폐기된다.
		assertThat(refresh(old).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(loginResponse(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		Device restricted = login(email, temporary);
		assertThat(restricted.restriction()).isEqualTo("MUST_CHANGE_PASSWORD");
		// 화이트리스트(me, password, logout) 밖은 막힌다.
		var blocked = call(HttpMethod.GET, "/api/v1/nodes", restricted, null);
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(blocked.getBody()).contains("ACCOUNT_RESTRICTED");
		assertThat(call(HttpMethod.POST, "/api/v1/nodes", restricted, Map.of("type", "NOTE", "title", "x")).getBody())
				.contains("ACCOUNT_RESTRICTED");
		var me = call(HttpMethod.GET, "/api/v1/auth/me", restricted, null);
		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(me.getBody(), "$.restriction")).isEqualTo("MUST_CHANGE_PASSWORD");

		assertThat(call(HttpMethod.PATCH, "/api/v1/auth/password", restricted,
				Map.of("currentPassword", temporary, "newPassword", "my-own-new-passw0rd")).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		// Access JWT는 무상태라 변경 직후에도 이전 restriction을 담고 있다. refresh하면 풀린다.
		assertThat(call(HttpMethod.GET, "/api/v1/nodes", restricted, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		var refreshed = refresh(restricted);
		String newToken = JsonPath.read(refreshed.getBody(), "$.accessToken");
		assertThat(claim(newToken, "restriction")).isEqualTo("NONE");
		assertThat(call(HttpMethod.GET, "/api/v1/nodes", new Device(email, newToken, null, null), null).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		// 감사: 누가·무엇을(임시 비밀번호는 기록하지 않는다)
		assertThat(auditMetadata(restricted.userId(), "ADMIN_FORCE_PASSWORD_RESET")).hasSize(1)
				.noneMatch(m -> m.contains(temporary));
	}

	// ---- reauth (17.2.2) -------------------------------------------------------------------

	@Test
	void reauthIssueValidatesPurposeAndTargetBeforeThePasswordAndNeverLeaksOtherWorkspaces() {
		Device device = signup(signupEmail(), PASSWORD);
		Device other = signup(signupEmail(), PASSWORD);
		String node = createNode(device, "A");
		String foreign = createNode(other, "B");

		assertThat(reauth(device, "NOT_A_PURPOSE", null, PASSWORD).getBody()).contains("INVALID_REAUTH_PURPOSE");
		assertThat(reauth(device, "NODE_PERMANENT_DELETE", null, PASSWORD).getBody()).contains("REAUTH_TARGET_REQUIRED");
		assertThat(reauth(device, "EXPORT_CREATE", node, PASSWORD).getBody()).contains("REAUTH_TARGET_NOT_ALLOWED");
		// 다른 Workspace의 Node는 없는 것과 같다.
		assertThat(reauth(device, "NODE_PERMANENT_DELETE", foreign, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(reauth(device, "NODE_PERMANENT_DELETE", UUID.randomUUID().toString(), PASSWORD).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		var wrong = reauth(device, "NODE_PERMANENT_DELETE", node, "wrong-password");
		assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrong.getBody()).contains("INVALID_CREDENTIALS");

		var ok = reauth(device, "NODE_PERMANENT_DELETE", node, PASSWORD);
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(ok.getBody(), "$.purpose")).isEqualTo("NODE_PERMANENT_DELETE");
		assertThat((String) JsonPath.read(ok.getBody(), "$.expiresAt")).isNotBlank();
		// 토큰은 해시로만 저장된다.
		String token = JsonPath.read(ok.getBody(), "$.reauthToken");
		assertThat(jdbc.queryForObject("select count(*) from reauth_tokens where encode(token_hash, 'escape') = ?", Integer.class, token))
				.isZero();
		// 실패와 성공 모두 감사 로그에 남고, 비밀번호·토큰은 없다.
		List<String> audit = auditMetadata(device.userId(), "AUTH_REAUTH_ISSUE");
		assertThat(audit).hasSize(2).noneMatch(m -> m.contains(token) || m.contains(PASSWORD) || m.contains("wrong-password"));
		assertThat(jdbc.queryForList("select outcome from security_audit_logs where actor_user_id = ? and event_type = 'AUTH_REAUTH_ISSUE' order by created_at",
				String.class, device.userId())).containsExactly("FAILURE", "SUCCESS");
	}

	@Test
	void permanentDeleteNeedsATrashedNodeAndAValidSingleUseReauthToken() {
		Device device = signup(signupEmail(), PASSWORD);
		String node = createNode(device, "To delete");
		String other = createNode(device, "Other");
		String token = reauthToken(device, "NODE_PERMANENT_DELETE", node);

		// 누락/엉터리 토큰은 같은 401
		assertThat(permanentDelete(device, node, null).getBody()).contains("REAUTH_REQUIRED");
		assertThat(permanentDelete(device, node, "garbage-token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 휴지통이 아니면 409이고, 이 실패가 토큰을 소진하지 않는다.
		var notTrashed = permanentDelete(device, node, token);
		assertThat(notTrashed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(notTrashed.getBody()).contains("INVALID_NODE_STATE");
		call(HttpMethod.POST, "/api/v1/nodes/" + node + "/trash", device, Map.of("version", 0));
		call(HttpMethod.POST, "/api/v1/nodes/" + other + "/trash", device, Map.of("version", 0));

		// 다른 대상에 쓰면 403(토큰은 소진되지 않는다).
		var wrongTarget = permanentDelete(device, other, token);
		assertThat(wrongTarget.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(wrongTarget.getBody()).contains("REAUTH_TARGET_MISMATCH");
		// 다른 purpose 토큰이면 403
		String exportToken = reauthToken(device, "EXPORT_CREATE", null);
		assertThat(permanentDelete(device, node, exportToken).getBody()).contains("REAUTH_PURPOSE_MISMATCH");

		// family당 토큰은 1개라 위의 재발급이 앞의 토큰을 대체했다.
		assertThat(permanentDelete(device, node, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		String fresh = reauthToken(device, "NODE_PERMANENT_DELETE", node);
		assertThat(permanentDelete(device, node, fresh).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(node))).isZero();
		// 1회용: 쓴 토큰은 대상을 조회하기 전에 401이다(이미 지워진 대상의 존재 여부를 드러내지 않는다).
		assertThat(permanentDelete(device, node, fresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(permanentDelete(device, node, reauthToken(device, "NODE_PERMANENT_DELETE", other)).getStatusCode())
				.isEqualTo(HttpStatus.FORBIDDEN); // 다른 대상용 토큰
		assertThat(permanentDelete(device, other, fresh).getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
		// ActivityLog에는 id와 action만 있고 제목은 없다.
		List<Map<String, Object>> logs = jdbc.queryForList("select action, object_id, safe_metadata from activity_logs where object_id = ? and action = 'NODE_PURGED'",
				UUID.fromString(node));
		assertThat(logs).hasSize(1);
		assertThat(String.valueOf(logs.get(0).get("safe_metadata"))).doesNotContain("To delete");
	}

	@Test
	void permanentDeleteCascadesToSubtypeTagsFavoritesViewsAndRelations() {
		Device device = signup(signupEmail(), PASSWORD);
		String snippet = JsonPath.read(call(HttpMethod.POST, "/api/v1/snippets", device, Map.of("title", "S", "language", "java",
				"code", "class A {}")).getBody(), "$.id");
		String concept = createNode(device, "C");
		String tag = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", device, Map.of("name", "t")).getBody(), "$.id");
		call(HttpMethod.PATCH, "/api/v1/snippets/" + snippet, device, Map.of("version", 0, "tagIds", List.of(tag)));
		call(HttpMethod.PUT, "/api/v1/nodes/" + snippet + "/favorite", device, null);
		call(HttpMethod.GET, "/api/v1/snippets/" + snippet, device, null); // 최근 조회 기록
		String typeId = JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/relation-types", device, null).getBody(),
				"$[?(@.key=='IS_EXAMPLE_OF')].id").get(0);
		assertThat(call(HttpMethod.POST, "/api/v1/relations", device,
				Map.of("sourceNodeId", snippet, "targetNodeId", concept, "relationTypeId", typeId)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		UUID id = UUID.fromString(snippet);
		int version = JsonPath.read(call(HttpMethod.GET, "/api/v1/snippets/" + snippet, device, null).getBody(), "$.version");
		assertThat(call(HttpMethod.POST, "/api/v1/nodes/" + snippet + "/trash", device, Map.of("version", version))
				.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(permanentDelete(device, snippet, reauthToken(device, "NODE_PERMANENT_DELETE", snippet)).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		for (String table : new String[] { "snippets", "snippet_versions", "node_tags", "favorites", "knowledge_relations" }) {
			String column = switch (table) {
				case "snippet_versions" -> "snippet_node_id";
				case "knowledge_relations" -> "source_node_id";
				default -> "node_id";
			};
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Integer.class, id))
					.as(table).isZero();
		}
		// 다른 Node와 태그 자체는 남는다.
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(concept))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from tags where id = ?", Integer.class, UUID.fromString(tag))).isEqualTo(1);
	}

	@Test
	void reauthTokensAreBoundToTheDeviceSessionAndDieWithIt() {
		String email = signupEmail();
		Device first = signup(email, PASSWORD);
		Device second = login(email, PASSWORD);
		String node = createNode(first, "N");
		call(HttpMethod.POST, "/api/v1/nodes/" + node + "/trash", first, Map.of("version", 0));
		String token = reauthToken(first, "NODE_PERMANENT_DELETE", node);

		// 다른 기기(family)의 토큰은 쓸 수 없다.
		assertThat(permanentDelete(second, node, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 만료되면 쓸 수 없다.
		jdbc.update("update reauth_tokens set expires_at = now() - interval '1 minute' where token_family_id = ?", first.familyId());
		assertThat(permanentDelete(first, node, token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 폐기된 세션의 토큰은 쓸 수 없다.
		String fresh = reauthToken(first, "NODE_PERMANENT_DELETE", node);
		assertThat(call(HttpMethod.DELETE, "/api/v1/auth/sessions/" + first.familyId(), second, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(permanentDelete(first, node, fresh).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(node))).isEqualTo(1);
	}

	@Test
	void concurrentConsumptionOfOneTokenSucceedsExactlyOnce() throws Exception {
		Device device = signup(signupEmail(), PASSWORD);
		String token = reauthToken(device, "EXPORT_CREATE", null);
		UUID workspaceId = jdbc.queryForObject("select workspace_id from workspace_members where user_id = ?", UUID.class, device.userId());
		AuthenticatedUser actor = new AuthenticatedUser(device.userId(), device.familyId(), "NONE");

		int contenders = 8;
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < contenders; i++) {
			results.add(pool.submit(() -> {
				start.await();
				try {
					reauthService.consume(actor, token, ReauthPurpose.EXPORT_CREATE, workspaceId, "127.0.0");
					return true;
				} catch (ApiException e) {
					assertThat(e.getCode()).isEqualTo("REAUTH_REQUIRED");
					return false;
				}
			}));
		}
		start.countDown();
		int successes = 0;
		for (Future<Boolean> f : results) {
			successes += f.get() ? 1 : 0;
		}
		pool.shutdown();
		assertThat(successes).isEqualTo(1);
	}

	// ---- account deletion (AUTH-06) --------------------------------------------------------

	@Test
	void accountDeletionIsReauthenticatedRestrictsTheAccountAndCanBeCancelledWithinTheGracePeriod() {
		String email = signupEmail();
		Device device = signup(email, PASSWORD);
		Device other = login(email, PASSWORD);
		createNode(device, "Keep me");

		assertThat(call(HttpMethod.POST, "/api/v1/account/deletion-request", device, Map.of("confirmation", email)).getBody())
				.contains("REAUTH_REQUIRED");
		String token = reauthToken(device, "ACCOUNT_DELETE_REQUEST", null);
		var mismatch = deletionRequest(device, "not-my-email@example.com", token);
		assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(mismatch.getBody()).contains("CONFIRMATION_MISMATCH");
		// 확인 문구가 틀려도 토큰은 소진되지 않는다(소비는 문구 검증 뒤).
		var requested = deletionRequest(device, email.toUpperCase(), token);
		assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.OK);
		Instant scheduledAt = Instant.parse(JsonPath.read(requested.getBody(), "$.scheduledAt"));
		assertThat(scheduledAt).isBetween(Instant.now().plus(6, ChronoUnit.DAYS).plusSeconds(3600 * 23), Instant.now().plus(8, ChronoUnit.DAYS));
		assertThat(requested.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("refresh_token=;");

		// 모든 세션이 폐기된다. 다시 로그인하면 제한 세션이다.
		assertThat(refresh(device).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(other).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		Device restricted = login(email, PASSWORD);
		assertThat(restricted.restriction()).isEqualTo("DELETION_PENDING");
		var blocked = call(HttpMethod.GET, "/api/v1/nodes", restricted, null);
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(blocked.getBody()).contains("ACCOUNT_RESTRICTED");
		assertThat(call(HttpMethod.POST, "/api/v1/account/deletion-request", restricted, Map.of("confirmation", email)).getBody())
				.contains("ACCOUNT_RESTRICTED");
		assertThat((String) JsonPath.read(call(HttpMethod.GET, "/api/v1/auth/me", restricted, null).getBody(), "$.restriction"))
				.isEqualTo("DELETION_PENDING");

		// 취소 → 제한 해제는 refresh 뒤에 반영된다.
		assertThat(call(HttpMethod.POST, "/api/v1/account/deletion-cancel", restricted, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(call(HttpMethod.POST, "/api/v1/account/deletion-cancel", restricted, null).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT); // 이미 ACTIVE
		Device active = login(email, PASSWORD);
		assertThat(active.restriction()).isEqualTo("NONE");
		assertThat(ids(active, "/api/v1/nodes")).hasSize(1);
		assertThat(jdbc.queryForObject("select deletion_requested_at from users where id = ?", Timestamp.class, active.userId())).isNull();
	}

	@Test
	void purgeRemovesOnlyAccountsPastTheGracePeriodAndEverythingTheyOwn() {
		String email = signupEmail();
		Device doomed = signup(email, PASSWORD);
		Device bystander = signup(signupEmail(), PASSWORD);
		String tag = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", doomed, Map.of("name", "bye")).getBody(), "$.id");
		call(HttpMethod.POST, "/api/v1/nodes", doomed, Map.of("type", "CONCEPT", "title", "Gone", "tagIds", List.of(tag)));
		call(HttpMethod.POST, "/api/v1/snippets", doomed, Map.of("title", "Gone code", "language", "java", "code", "class A {}"));
		call(HttpMethod.POST, "/api/v1/errors", doomed, Map.of("title", "Gone err", "errorMessage", "m"));
		createNode(bystander, "Stay");
		UUID userId = doomed.userId();
		UUID workspaceId = jdbc.queryForObject("select workspace_id from workspace_members where user_id = ?", UUID.class, userId);

		deletionRequest(doomed, email, reauthToken(doomed, "ACCOUNT_DELETE_REQUEST", null));
		// 유예 안에서는 지우지 않는다.
		assertThat(purgeService.purgeDue(Instant.now())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId)).isEqualTo(1);

		jdbc.update("update users set deletion_requested_at = now() - interval '8 days' where id = ?", userId);
		assertThat(purgeService.purgeDue(Instant.now())).isEqualTo(1);

		for (String check : new String[] {
				"select count(*) from users where id = '" + userId + "'",
				"select count(*) from workspaces where id = '" + workspaceId + "'",
				"select count(*) from workspace_members where user_id = '" + userId + "'",
				"select count(*) from knowledge_nodes where workspace_id = '" + workspaceId + "'",
				"select count(*) from tags where workspace_id = '" + workspaceId + "'",
				"select count(*) from activity_logs where workspace_id = '" + workspaceId + "'",
				"select count(*) from snippet_versions where workspace_id = '" + workspaceId + "'",
				"select count(*) from error_records where workspace_id = '" + workspaceId + "'",
				"select count(*) from auth_session_families where user_id = '" + userId + "'" }) {
			assertThat(jdbc.queryForObject(check, Integer.class)).as(check).isZero();
		}
		// 다른 사용자의 데이터는 그대로다.
		assertThat(ids(bystander, "/api/v1/nodes")).hasSize(1);
		// 감사 로그는 익명화되어 남고(식별자 제거) 삭제 사건이 기록된다.
		assertThat(jdbc.queryForObject("select count(*) from security_audit_logs where actor_user_id = ?", Integer.class, userId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from security_audit_logs where event_type = 'ACCOUNT_PURGED'", Integer.class))
				.isPositive();
		assertThat(loginResponse(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// 같은 이메일로 다시 가입할 수 있다.
		assertThat(signupResponse(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ---- retention (21.5) ------------------------------------------------------------------

	@Test
	void retentionRemovesOnlyDataPastItsRetentionPeriod() {
		Device device = signup(signupEmail(), PASSWORD);
		UUID userId = device.userId();
		String old = createNode(device, "Old trash");
		String recent = createNode(device, "Recent trash");
		String live = createNode(device, "Live");
		for (String id : List.of(old, recent)) {
			call(HttpMethod.POST, "/api/v1/nodes/" + id + "/trash", device, Map.of("version", 0));
		}
		jdbc.update("update knowledge_nodes set trashed_at = now() - interval '31 days' where id = ?", UUID.fromString(old));
		jdbc.update("update knowledge_nodes set trashed_at = now() - interval '29 days' where id = ?", UUID.fromString(recent));
		UUID workspaceId = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, UUID.fromString(live));
		jdbc.update("insert into activity_logs (workspace_id, actor_user_id, action, object_type, object_id, created_at) values (?, ?, 'TEST_OLD', 'NODE', gen_random_uuid(), now() - interval '181 days')", workspaceId, userId);
		jdbc.update("insert into activity_logs (workspace_id, actor_user_id, action, object_type, object_id, created_at) values (?, ?, 'TEST_NEW', 'NODE', gen_random_uuid(), now() - interval '179 days')", workspaceId, userId);
		jdbc.update("insert into security_audit_logs (actor_user_id, event_type, outcome, created_at) values (?, 'TEST_OLD', 'SUCCESS', now() - interval '366 days')", userId);
		jdbc.update("insert into security_audit_logs (actor_user_id, event_type, outcome, created_at) values (?, 'TEST_NEW', 'SUCCESS', now() - interval '364 days')", userId);
		reauthToken(device, "EXPORT_CREATE", null);
		jdbc.update("update reauth_tokens set expires_at = now() - interval '1 hour' where token_family_id = ?", device.familyId());

		Map<String, Integer> result = retentionService.runAll(Instant.now());

		assertThat(result.get("trashedNodes")).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(old))).isZero();
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(recent))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, UUID.fromString(live))).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from activity_logs where action = 'TEST_OLD' and workspace_id = ?", Integer.class, workspaceId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from activity_logs where action = 'TEST_NEW' and workspace_id = ?", Integer.class, workspaceId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from security_audit_logs where event_type = 'TEST_OLD' and actor_user_id = ?", Integer.class, userId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from security_audit_logs where event_type = 'TEST_NEW' and actor_user_id = ?", Integer.class, userId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from reauth_tokens where token_family_id = ?", Integer.class, device.familyId())).isZero();
		// 멱등: 한 번 더 돌려도 더 지울 것이 없다.
		assertThat(retentionService.runAll(Instant.now()).get("trashedNodes")).isZero();
	}

	// ---- response security headers (17.7) --------------------------------------------------

	@Test
	void everyApiResponseCarriesTheSecurityHeaders() {
		Device device = signup(signupEmail(), PASSWORD);
		for (var response : List.of(call(HttpMethod.GET, "/api/v1/nodes", device, null),
				call(HttpMethod.GET, "/api/v1/nodes/" + UUID.randomUUID(), device, null), // 404
				restTemplate.getForEntity(baseUrl("/api/v1/nodes"), String.class), // 401
				loginResponse("nobody@example.com", PASSWORD))) {
			HttpHeaders h = response.getHeaders();
			assertThat(h.getFirst("Content-Security-Policy")).isEqualTo("default-src 'self'; script-src 'self'; "
					+ "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
			assertThat(h.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
			assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
			assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
			assertThat(h.getFirst("Permissions-Policy")).isEqualTo("geolocation=(), camera=(), microphone=()");
			// 개인 데이터 응답이 중간 캐시에 남지 않는다.
			assertThat(h.getCacheControl()).contains("no-store");
			// HSTS는 HTTPS 요청에만 붙는다(이 테스트는 http라 없다).
			assertThat(h.getFirst("Strict-Transport-Security")).isNull();
		}
	}

	// ---- helpers ----------------------------------------------------------------------------

	private List<String> auditMetadata(UUID userId, String eventType) {
		return jdbc.queryForList("select coalesce(metadata::text, '') from security_audit_logs where actor_user_id = ? and event_type = ? order by created_at",
				String.class, userId, eventType);
	}

	private ResponseEntity<String> reauth(Device device, String purpose, String targetId, String password) {
		Map<String, Object> body = new HashMap<>();
		body.put("password", password);
		body.put("purpose", purpose);
		if (targetId != null) {
			body.put("targetId", targetId);
		}
		return call(HttpMethod.POST, "/api/v1/auth/reauth", device, body);
	}

	private String reauthToken(Device device, String purpose, String targetId) {
		var response = reauth(device, purpose, targetId, PASSWORD);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return JsonPath.read(response.getBody(), "$.reauthToken");
	}

	private ResponseEntity<String> permanentDelete(Device device, String nodeId, String reauthToken) {
		HttpHeaders headers = bearer(device);
		if (reauthToken != null) {
			headers.add("X-Reauth-Token", reauthToken);
		}
		return restTemplate.exchange(java.net.URI.create(baseUrl("/api/v1/nodes/" + nodeId + "/permanent-delete")),
				HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
	}

	private ResponseEntity<String> deletionRequest(Device device, String confirmation, String reauthToken) {
		HttpHeaders headers = bearer(device);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.add("X-Reauth-Token", reauthToken);
		return restTemplate.exchange(java.net.URI.create(baseUrl("/api/v1/account/deletion-request")), HttpMethod.POST,
				new HttpEntity<>(Map.of("confirmation", confirmation), headers), String.class);
	}

	private String createNode(Device device, String title) {
		var response = call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "CONCEPT", "title", title));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private List<String> ids(Device device, String path) {
		return JsonPath.read(call(HttpMethod.GET, path, device, null).getBody(), "$.items[*].id");
	}

	private HttpHeaders bearer(Device device) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(device.accessToken());
		return headers;
	}

	private ResponseEntity<String> call(HttpMethod method, String path, Device device, Object body) {
		HttpHeaders headers = bearer(device);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(java.net.URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> refresh(Device device) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "refresh_token=" + device.refreshCookie() + "; csrf_token=" + device.csrfCookie());
		headers.add("X-CSRF-Token", device.csrfCookie());
		// 브라우저 클라이언트는 만료/제한된 access token을 refresh 요청에도 붙인다. 제한 세션도 refresh로 풀려야 한다.
		if (device.accessToken() != null) {
			headers.setBearerAuth(device.accessToken());
		}
		return restTemplate.exchange(baseUrl("/api/v1/auth/refresh"), HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
	}

	private ResponseEntity<String> signupResponse(String email, String password) {
		return post("/api/v1/auth/signup", Map.of("email", email, "displayName", "Account Tester", "password", password));
	}

	private ResponseEntity<String> loginResponse(String email, String password) {
		return post("/api/v1/auth/login", Map.of("email", email, "password", password));
	}

	private ResponseEntity<String> post(String path, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.postForEntity(baseUrl(path), new HttpEntity<>(body, headers), String.class);
	}

	private Device signup(String email, String password) {
		var response = signupResponse(email, password);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return device(email, response);
	}

	private Device login(String email, String password) {
		var response = loginResponse(email, password);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return device(email, response);
	}

	private Device device(String email, ResponseEntity<String> response) {
		List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		return new Device(email, JsonPath.read(response.getBody(), "$.accessToken"), cookie(cookies, "refresh_token"),
				cookie(cookies, "csrf_token"));
	}

	private static String cookie(List<String> setCookies, String name) {
		return setCookies.stream().filter(c -> c.startsWith(name + "="))
				.map(c -> c.substring(name.length() + 1, c.indexOf(';'))).findFirst().orElseThrow();
	}

	private static String claim(String jwt, String name) {
		String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
		return JsonPath.read(payload, "$." + name);
	}

	private static String signupEmail() {
		return "acct-" + UUID.randomUUID() + "@example.com";
	}
}
