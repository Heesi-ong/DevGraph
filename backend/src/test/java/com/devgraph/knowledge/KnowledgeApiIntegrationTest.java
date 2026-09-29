package com.devgraph.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * 설계서 §19 Phase 2 / §20 테스트: validation, pagination, version conflict, archive visibility,
 * cross-workspace access, 활동 로그/최근 조회 기록.
 */
class KnowledgeApiIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	// ---- validation -------------------------------------------------------------------------

	@Test
	void createRejectsBlankTitleUnsupportedTypeAndOversizedBody() {
		String token = signupToken();

		var blank = call(HttpMethod.POST, "/api/v1/nodes", token, "{\"type\":\"NOTE\",\"title\":\"  \"}");
		assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(blank.getBody()).contains("VALIDATION_FAILED").contains("\"title\"");

		var unsupported = call(HttpMethod.POST, "/api/v1/nodes", token, "{\"type\":\"PROJECT\",\"title\":\"x\"}");
		assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		String bigBody = "a".repeat(1_000_001);
		var big = call(HttpMethod.POST, "/api/v1/nodes", token,
				"{\"type\":\"NOTE\",\"title\":\"x\",\"bodyMd\":\"" + bigBody + "\"}");
		assertThat(big.getStatusCode().value()).isEqualTo(413);
		assertThat(big.getBody()).contains("PAYLOAD_TOO_LARGE");
	}

	@Test
	void unauthenticatedRequestIsRejected() {
		var response = restTemplate.getForEntity(baseUrl("/api/v1/nodes"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---- pagination -------------------------------------------------------------------------

	@Test
	void cursorPaginationWalksAllItemsOnceInUpdatedAtDescOrder() {
		String token = signupToken();
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(createNode(token, "NOTE", "n" + i));
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			String path = "/api/v1/nodes?size=2" + (cursor == null ? "" : "&cursor=" + cursor);
			var response = call(HttpMethod.GET, path, token, null);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			seen.addAll(JsonPath.<List<String>>read(response.getBody(), "$.items[*].id"));
			boolean hasMore = JsonPath.read(response.getBody(), "$.hasMore");
			cursor = hasMore ? JsonPath.read(response.getBody(), "$.cursor") : null;
			pages++;
		} while (cursor != null && pages < 10);

		assertThat(pages).isEqualTo(3);
		assertThat(seen).hasSize(5).doesNotHaveDuplicates();
		// 마지막에 만든 것이 먼저 나온다.
		java.util.Collections.reverse(created);
		assertThat(seen).containsExactlyElementsOf(created);
	}

	@Test
	void paginationRejectsInvalidSizeAndCursor() {
		String token = signupToken();
		assertThat(call(HttpMethod.GET, "/api/v1/nodes?size=0", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/nodes?size=101", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		var badCursor = call(HttpMethod.GET, "/api/v1/nodes?cursor=not-a-cursor", token, null);
		assertThat(badCursor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(badCursor.getBody()).contains("INVALID_CURSOR");
	}

	// ---- version conflict -------------------------------------------------------------------

	@Test
	void staleVersionUpdateReturns409AndKeepsServerContent() {
		String token = signupToken();
		String id = createNode(token, "CONCEPT", "original");

		var first = call(HttpMethod.PATCH, "/api/v1/nodes/" + id, token, "{\"version\":0,\"title\":\"first\"}");
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((Integer) JsonPath.read(first.getBody(), "$.version")).isEqualTo(1);

		var stale = call(HttpMethod.PATCH, "/api/v1/nodes/" + id, token, "{\"version\":0,\"title\":\"stale\"}");
		assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(stale.getBody()).contains("VERSION_CONFLICT");

		var latest = call(HttpMethod.GET, "/api/v1/nodes/" + id, token, null);
		assertThat((String) JsonPath.read(latest.getBody(), "$.title")).isEqualTo("first");
	}

	// ---- archive visibility / lifecycle -----------------------------------------------------

	@Test
	void archivedAndTrashedNodesAreHiddenFromDefaultListButFilterable() {
		String token = signupToken();
		String keep = createNode(token, "NOTE", "keep");
		String archived = createNode(token, "NOTE", "archived");
		String trashed = createNode(token, "NOTE", "trashed");

		assertThat(transition(token, archived, "archive", 0).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(transition(token, trashed, "trash", 0).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(ids(token, "/api/v1/nodes")).containsExactly(keep);
		assertThat(ids(token, "/api/v1/nodes?status=ARCHIVED")).containsExactly(archived);
		assertThat(ids(token, "/api/v1/nodes?status=TRASHED")).containsExactly(trashed);
	}

	@Test
	void statusTransitionsFollowTheStateMachine() {
		String token = signupToken();
		String id = createNode(token, "NOTE", "life");

		// ACTIVE에서 곧바로 trash/restore는 불가
		assertThat(transition(token, id, "trash/restore", 0).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(transition(token, id, "trash", 0).getStatusCode()).isEqualTo(HttpStatus.OK);
		// TRASHED 수정 불가
		var edit = call(HttpMethod.PATCH, "/api/v1/nodes/" + id, token, "{\"version\":1,\"title\":\"x\"}");
		assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(edit.getBody()).contains("INVALID_NODE_STATE");

		// TRASHED → ARCHIVED (ACTIVE로 직접 복구하지 않는다)
		var restored = transition(token, id, "trash/restore", 1);
		assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(restored.getBody(), "$.status")).isEqualTo("ARCHIVED");

		var active = transition(token, id, "archive/restore", 2);
		assertThat((String) JsonPath.read(active.getBody(), "$.status")).isEqualTo("ACTIVE");
	}

	@Test
	void transitionWithStaleVersionReturns409() {
		String token = signupToken();
		String id = createNode(token, "NOTE", "v");
		var response = transition(token, id, "archive", 5);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).contains("VERSION_CONFLICT");
	}

	// ---- tags / favorites -------------------------------------------------------------------

	@Test
	void tagsAreNormalizedFilterableAndFavoritesAreIdempotent() {
		String token = signupToken();
		var tag = call(HttpMethod.POST, "/api/v1/tags", token, "{\"name\":\"  Spring   Boot \"}");
		assertThat(tag.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String tagId = JsonPath.read(tag.getBody(), "$.id");
		assertThat((String) JsonPath.read(tag.getBody(), "$.name")).isEqualTo("Spring Boot");
		assertThat(call(HttpMethod.POST, "/api/v1/tags", token, "{\"name\":\"spring boot\"}").getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);

		var tagged = call(HttpMethod.POST, "/api/v1/nodes", token,
				"{\"type\":\"NOTE\",\"title\":\"t\",\"tagIds\":[\"" + tagId + "\"]}");
		String taggedId = JsonPath.read(tagged.getBody(), "$.id");
		createNode(token, "NOTE", "untagged");

		assertThat(ids(token, "/api/v1/nodes?tagId=" + tagId)).containsExactly(taggedId);

		assertThat(call(HttpMethod.PUT, "/api/v1/nodes/" + taggedId + "/favorite", token, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(call(HttpMethod.PUT, "/api/v1/nodes/" + taggedId + "/favorite", token, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(ids(token, "/api/v1/nodes?favorite=true")).containsExactly(taggedId);
		call(HttpMethod.DELETE, "/api/v1/nodes/" + taggedId + "/favorite", token, null);
		assertThat(ids(token, "/api/v1/nodes?favorite=true")).isEmpty();
	}

	// ---- cross-workspace --------------------------------------------------------------------

	@Test
	void otherWorkspaceCannotReadModifyOrReferenceNodesAndTags() {
		String owner = signupToken();
		String intruder = signupToken();
		String nodeId = createNode(owner, "NOTE", "secret");
		var tag = call(HttpMethod.POST, "/api/v1/tags", owner, "{\"name\":\"private\"}");
		String tagId = JsonPath.read(tag.getBody(), "$.id");

		assertThat(call(HttpMethod.GET, "/api/v1/nodes/" + nodeId, intruder, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PATCH, "/api/v1/nodes/" + nodeId, intruder, "{\"version\":0,\"title\":\"x\"}")
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(transition(intruder, nodeId, "trash", 0).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PUT, "/api/v1/nodes/" + nodeId + "/favorite", intruder, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.POST, "/api/v1/nodes", intruder,
				"{\"type\":\"NOTE\",\"title\":\"x\",\"tagIds\":[\"" + tagId + "\"]}").getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(ids(intruder, "/api/v1/nodes")).isEmpty();
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/tags", intruder, null).getBody(), "$.items"))
				.isEmpty();

		// 원본은 변하지 않았다.
		var original = call(HttpMethod.GET, "/api/v1/nodes/" + nodeId, owner, null);
		assertThat((String) JsonPath.read(original.getBody(), "$.title")).isEqualTo("secret");
		assertThat((String) JsonPath.read(original.getBody(), "$.status")).isEqualTo("ACTIVE");
	}

	// ---- side effects: activity log, recent view --------------------------------------------

	@Test
	void mutationsWriteActivityLogsAndDetailViewRecordsRecentView() throws InterruptedException {
		String token = signupToken();
		String id = createNode(token, "NOTE", "logged");
		call(HttpMethod.PATCH, "/api/v1/nodes/" + id, token, "{\"version\":0,\"title\":\"logged2\"}");
		call(HttpMethod.GET, "/api/v1/nodes/" + id, token, null);

		UUID nodeId = UUID.fromString(id);
		awaitCount("select count(*) from activity_logs where object_id = ?", nodeId, 2);
		awaitCount("select count(*) from node_views where node_id = ?", nodeId, 1);
		List<String> actions = jdbc.queryForList(
				"select action from activity_logs where object_id = ? order by created_at", String.class, nodeId);
		assertThat(actions).containsExactly("NODE_CREATED", "NODE_UPDATED");
	}

	// ---- helpers ----------------------------------------------------------------------------

	private void awaitCount(String sql, UUID arg, int expected) throws InterruptedException {
		Integer count = 0;
		for (int i = 0; i < 50; i++) { // 이벤트/비동기 기록은 커밋 이후라 최대 5초 기다린다.
			count = jdbc.queryForObject(sql, Integer.class, arg);
			if (count != null && count >= expected) {
				return;
			}
			Thread.sleep(100);
		}
		assertThat(count).as(sql).isGreaterThanOrEqualTo(expected);
	}

	private List<String> ids(String token, String path) {
		return JsonPath.read(call(HttpMethod.GET, path, token, null).getBody(), "$.items[*].id");
	}

	private ResponseEntity<String> transition(String token, String id, String action, long version) {
		return call(HttpMethod.POST, "/api/v1/nodes/" + id + "/" + action, token, "{\"version\":" + version + "}");
	}

	private String createNode(String token, String type, String title) {
		var response = call(HttpMethod.POST, "/api/v1/nodes", token,
				"{\"type\":\"" + type + "\",\"title\":\"" + title + "\"}");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private ResponseEntity<String> call(HttpMethod method, String path, String token, String json) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		if (json != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(baseUrl(path), method, new HttpEntity<>(json, headers), String.class);
	}

	private String signupToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>("""
				{"email":"k-%s@example.com","displayName":"Knowledge Tester","password":"correct-horse-battery"}"""
				.formatted(UUID.randomUUID()), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
