package com.devgraph.snippet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * 설계서 §19 Phase 3 테스트: code size/encoding, version concurrency, exact code round-trip,
 * dangerous content non-execution(서버 측: 원문 그대로 저장·JSON으로만 반환). 완료 조건: v1 생성, 코드 수정 시 v2,
 * metadata 수정 시 불필요한 version 미생성, 이전 원문 조회 가능.
 */
class SnippetApiIntegrationTest extends AbstractIntegrationTest {

	private static final String CODE = "public class A {\r\n\tString s = \"한글 😀 <b>&amp;</b>\";\r\n}\r\n  ";

	@Autowired
	JdbcTemplate jdbc;

	// ---- lifecycle / versioning -------------------------------------------------------------

	@Test
	void createStoresCodeExactlyAndCreatesVersionOne() {
		String token = signupToken();
		var created = call(HttpMethod.POST, "/api/v1/snippets", token, snippet("Exact", "Java", CODE));

		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(created.getBody(), "$.type")).isEqualTo("SNIPPET");
		assertThat((Integer) JsonPath.read(created.getBody(), "$.snippet.currentVersionNo")).isEqualTo(1);
		assertThat((String) JsonPath.read(created.getBody(), "$.snippet.language")).isEqualTo("java");
		// 공백·CRLF·탭·이모지까지 한 글자도 바뀌지 않는다.
		assertThat((String) JsonPath.read(created.getBody(), "$.currentVersion.code")).isEqualTo(CODE);

		String id = JsonPath.read(created.getBody(), "$.id");
		var detail = call(HttpMethod.GET, "/api/v1/snippets/" + id, token, null);
		assertThat((String) JsonPath.read(detail.getBody(), "$.currentVersion.code")).isEqualTo(CODE);
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions", token, null)
				.getBody(), "$.items")).hasSize(1);
	}

	@Test
	void codeChangeCreatesNewVersionButMetadataChangeDoesNot() {
		String token = signupToken();
		String id = createSnippet(token, "Life", "java", "v1 code");

		// 코드 변경 → v2
		var v2 = patch(token, id, 0, Map.of("code", "v2 code", "changeSummary", "리팩터링"));
		assertThat((Integer) JsonPath.read(v2.getBody(), "$.snippet.currentVersionNo")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(v2.getBody(), "$.version")).isEqualTo(1);
		assertThat((String) JsonPath.read(v2.getBody(), "$.currentVersion.changeSummary")).isEqualTo("리팩터링");

		// 제목만 변경 → 버전 유지, Node version만 증가
		var meta = patch(token, id, 1, Map.of("title", "Life renamed", "framework", "Spring"));
		assertThat((Integer) JsonPath.read(meta.getBody(), "$.snippet.currentVersionNo")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(meta.getBody(), "$.version")).isEqualTo(2);
		assertThat((String) JsonPath.read(meta.getBody(), "$.snippet.framework")).isEqualTo("spring");

		// 같은 코드를 다시 보내도 새 버전이 없고, 아무것도 안 바뀌면 version도 그대로
		var same = patch(token, id, 2, Map.of("code", "v2 code", "title", "Life renamed"));
		assertThat((Integer) JsonPath.read(same.getBody(), "$.snippet.currentVersionNo")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(same.getBody(), "$.version")).isEqualTo(2);

		// 언어만 변경도 코드 버전은 늘지 않는다.
		var lang = patch(token, id, 2, Map.of("language", "kotlin"));
		assertThat((Integer) JsonPath.read(lang.getBody(), "$.snippet.currentVersionNo")).isEqualTo(2);

		var versions = call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions", token, null);
		assertThat(JsonPath.<List<Integer>>read(versions.getBody(), "$.items[*].versionNo")).containsExactly(2, 1);

		// 이전 원문 조회
		var old = call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions/1", token, null);
		assertThat((String) JsonPath.read(old.getBody(), "$.code")).isEqualTo("v1 code");
		assertThat(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions/9", token, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---- validation: size / encoding --------------------------------------------------------

	@Test
	void diffShowsLineChangesBetweenVersionsAndRespectsLimitsAndIsolation() {
		String token = signupToken();
		String id = createSnippet(token, "Diff", "java", "class A {\n  int x = 1;\n  int y = 2;\n}\n");
		patch(token, id, 0, Map.of("code", "class A {\n  int x = 10;\n  int y = 2;\n  int z = 3;\n}\n"));

		var diff = call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=1&to=2", token, null);
		assertThat(diff.getStatusCode()).as(diff.getBody()).isEqualTo(HttpStatus.OK);
		assertThat((Integer) JsonPath.read(diff.getBody(), "$.added")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(diff.getBody(), "$.deleted")).isEqualTo(1);
		assertThat(JsonPath.<List<String>>read(diff.getBody(), "$.hunks[0].lines[?(@.type=='DELETE')].text")).containsExactly("  int x = 1;");
		assertThat(JsonPath.<List<String>>read(diff.getBody(), "$.hunks[0].lines[?(@.type=='ADD')].text"))
				.containsExactly("  int x = 10;", "  int z = 3;");
		// 반대 방향은 추가/삭제가 뒤바뀐다. 같은 버전끼리는 변경이 없다.
		var reverse = call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=2&to=1", token, null);
		assertThat((Integer) JsonPath.read(reverse.getBody(), "$.deleted")).isEqualTo(2);
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=2&to=2", token, null).getBody(), "$.hunks")).isEmpty();
		// 없는 버전 404, 잘못된 번호 400, 다른 Workspace 404.
		assertThat(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=1&to=9", token, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=0&to=1", token, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/diff?from=1&to=2", signupToken(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// 편집 거리가 상한을 넘으면 계산하지 않고 413.
		StringBuilder a = new StringBuilder();
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < 2500; i++) {
			a.append("old").append(i).append('\n');
			b.append("new").append(i).append('\n');
		}
		String big = createSnippet(token, "Big", "java", a.toString());
		patch(token, big, 0, Map.of("code", b.toString()));
		var tooLarge = call(HttpMethod.GET, "/api/v1/snippets/" + big + "/diff?from=1&to=2", token, null);
		assertThat(tooLarge.getStatusCode().value()).isEqualTo(413);
		assertThat(tooLarge.getBody()).contains("DIFF_TOO_LARGE");
	}

	@Test
	void rejectsInvalidCodeAndMetadata() {
		String token = signupToken();
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet("t", "java", "   ")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		var nul = call(HttpMethod.POST, "/api/v1/snippets", token, snippet("t", "java", "a\u0000b"));
		assertThat(nul.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(nul.getBody()).contains("INVALID_CHARACTER");
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet("t", "java", "x\uD800y")).getStatusCode()
				.is4xxClientError()).isTrue();
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet("t", "not a language!", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet(" ", "java", "x")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void enforcesCodeSizeLimitInBytesNotCharacters() {
		String token = signupToken();
		String atLimit = "a".repeat(512 * 1024);
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet("ok", "text", atLimit)).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		var over = call(HttpMethod.POST, "/api/v1/snippets", token, snippet("big", "text", atLimit + "a"));
		assertThat(over.getStatusCode().value()).isEqualTo(413);
		assertThat(over.getBody()).contains("PAYLOAD_TOO_LARGE").contains("MAX_512KB");

		// 글자 수는 한도 이하지만 UTF-8 바이트는 초과하는 경우(한글 3바이트)
		String multibyte = "가".repeat(180_000);
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, snippet("mb", "text", multibyte))
				.getStatusCode().value()).isEqualTo(413);
	}

	// ---- secret detection -------------------------------------------------------------------

	@Test
	void secretsRequireConfirmationAndNeverEchoTheValue() {
		String token = signupToken();
		String code = "String a = 1;\npassword = \"hunter2hunter2\"\n";

		var blocked = call(HttpMethod.POST, "/api/v1/snippets", token, snippet("s", "java", code));
		assertThat(blocked.getStatusCode().value()).isEqualTo(422);
		assertThat(blocked.getBody()).contains("SECRET_CONFIRMATION_REQUIRED").contains("CREDENTIAL_ASSIGNMENT@L2");
		assertThat(blocked.getBody()).doesNotContain("hunter2hunter2");
		assertThat(ids(token, "/api/v1/snippets")).isEmpty(); // 아무것도 저장되지 않았다.

		Map<String, Object> confirmed = snippet("s", "java", code);
		confirmed.put("secretConfirmation", "CONFIRMED");
		var ok = call(HttpMethod.POST, "/api/v1/snippets", token, confirmed);
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(ok.getBody(), "$.snippet.secretScanStatus")).isEqualTo("CONFIRMED_WITH_FINDINGS");
	}

	@Test
	void privateKeyNeedsHighRiskReconfirmationAndCodeUpdateIsRescanned() {
		String token = signupToken();
		String key = "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----";

		Map<String, Object> normalConfirm = snippet("k", "text", key);
		normalConfirm.put("secretConfirmation", "CONFIRMED");
		assertThat(call(HttpMethod.POST, "/api/v1/snippets", token, normalConfirm).getStatusCode().value())
				.isEqualTo(422);

		Map<String, Object> highConfirm = snippet("k", "text", key);
		highConfirm.put("secretConfirmation", "CONFIRMED_HIGH_RISK");
		var created = call(HttpMethod.POST, "/api/v1/snippets", token, highConfirm);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String id = JsonPath.read(created.getBody(), "$.id");

		// 수정으로 secret을 제거하면 상태가 CLEAN으로 돌아가고, 새 secret을 넣으면 다시 확인을 요구한다.
		var cleaned = patch(token, id, 0, Map.of("code", "no secret here"));
		assertThat((String) JsonPath.read(cleaned.getBody(), "$.snippet.secretScanStatus")).isEqualTo("CLEAN");
		var again = call(HttpMethod.PATCH, "/api/v1/snippets/" + id, token,
				Map.of("version", 1, "code", "token = \"abcdefgh12345678\""));
		assertThat(again.getStatusCode().value()).isEqualTo(422);
	}

	// ---- optimistic locking / concurrency ---------------------------------------------------

	@Test
	void staleVersionReturns409AndLeavesVersionsUntouched() {
		String token = signupToken();
		String id = createSnippet(token, "Stale", "java", "one");
		patch(token, id, 0, Map.of("code", "two"));

		var stale = call(HttpMethod.PATCH, "/api/v1/snippets/" + id, token, Map.of("version", 0, "code", "three"));
		assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(stale.getBody()).contains("VERSION_CONFLICT");
		assertThat(JsonPath.<List<Integer>>read(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions", token,
				null).getBody(), "$.items[*].versionNo")).containsExactly(2, 1);
	}

	@Test
	void concurrentCodeUpdatesWithSameVersionAllowExactlyOneWinner() throws Exception {
		String token = signupToken();
		String id = createSnippet(token, "Race", "java", "base");

		int contenders = 4;
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpStatus>> results = new ArrayList<>();
		for (int i = 0; i < contenders; i++) {
			String code = "contender " + i;
			results.add(pool.submit(() -> {
				start.await();
				return HttpStatus.valueOf(call(HttpMethod.PATCH, "/api/v1/snippets/" + id, token,
						Map.of("version", 0, "code", code)).getStatusCode().value());
			}));
		}
		start.countDown();
		List<HttpStatus> statuses = new ArrayList<>();
		for (Future<HttpStatus> f : results) {
			statuses.add(f.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == HttpStatus.OK)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == HttpStatus.CONFLICT)).hasSize(contenders - 1);
		// 버전 번호는 연속이고 중복이 없다(v1, v2 뿐).
		assertThat(JsonPath.<List<Integer>>read(call(HttpMethod.GET, "/api/v1/snippets/" + id + "/versions", token,
				null).getBody(), "$.items[*].versionNo")).containsExactly(2, 1);
	}

	// ---- listing / filters ------------------------------------------------------------------

	@Test
	void listFiltersByLanguageFrameworkTagFavoriteAndStatusWithCursorPaging() {
		String token = signupToken();
		var tag = call(HttpMethod.POST, "/api/v1/tags", token, Map.of("name", "auth"));
		String tagId = JsonPath.read(tag.getBody(), "$.id");

		String java1 = createSnippet(token, "J1", "java", "a");
		String ts1 = createSnippet(token, "T1", "typescript", "b");
		Map<String, Object> springSnippet = snippet("S1", "java", "c");
		springSnippet.put("framework", "spring");
		springSnippet.put("tagIds", List.of(tagId));
		String s1 = JsonPath.read(call(HttpMethod.POST, "/api/v1/snippets", token, springSnippet).getBody(), "$.id");

		assertThat(ids(token, "/api/v1/snippets?language=java")).containsExactlyInAnyOrder(java1, s1);
		assertThat(ids(token, "/api/v1/snippets?language=JAVA&framework=Spring")).containsExactly(s1);
		assertThat(ids(token, "/api/v1/snippets?tagId=" + tagId)).containsExactly(s1);

		call(HttpMethod.PUT, "/api/v1/nodes/" + ts1 + "/favorite", token, null);
		assertThat(ids(token, "/api/v1/snippets?favorite=true")).containsExactly(ts1);

		// 상태 전이는 Node 공통 endpoint를 쓰고, 보관하면 기본 목록에서 빠진다.
		assertThat(call(HttpMethod.POST, "/api/v1/nodes/" + java1 + "/archive", token, Map.of("version", 0))
				.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ids(token, "/api/v1/snippets")).doesNotContain(java1);
		assertThat(ids(token, "/api/v1/snippets?status=ARCHIVED")).containsExactly(java1);

		// cursor 페이징: 남은 2건을 size=1로 순회
		var first = call(HttpMethod.GET, "/api/v1/snippets?size=1", token, null);
		assertThat((Boolean) JsonPath.read(first.getBody(), "$.hasMore")).isTrue();
		String cursor = JsonPath.read(first.getBody(), "$.cursor");
		var second = call(HttpMethod.GET, "/api/v1/snippets?size=1&cursor=" + cursor, token, null);
		assertThat((Boolean) JsonPath.read(second.getBody(), "$.hasMore")).isFalse();
		assertThat(JsonPath.<List<String>>read(first.getBody(), "$.items[*].id"))
				.doesNotContainAnyElementsOf(JsonPath.<List<String>>read(second.getBody(), "$.items[*].id"));
		// 목록은 코드 원문을 싣지 않는다.
		assertThat(first.getBody()).doesNotContain("\"code\"");
	}

	// ---- isolation between node types and workspaces ----------------------------------------

	@Test
	void snippetsAreSeparateFromLibraryAndCannotBeEditedThroughNodeApi() {
		String token = signupToken();
		String snippetId = createSnippet(token, "Hidden", "java", "x");
		String conceptId = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "CONCEPT", "title", "Concept")).getBody(), "$.id");

		assertThat(ids(token, "/api/v1/nodes")).containsExactly(conceptId);
		assertThat(call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "SNIPPET", "title", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		var viaNodeApi = call(HttpMethod.PATCH, "/api/v1/nodes/" + snippetId, token,
				Map.of("version", 0, "title", "bypass"));
		assertThat(viaNodeApi.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(viaNodeApi.getBody()).contains("USE_SUBTYPE_API");

		// 반대로 Concept은 Snippet API에서 보이지 않는다.
		assertThat(call(HttpMethod.GET, "/api/v1/snippets/" + conceptId, token, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PATCH, "/api/v1/snippets/" + conceptId, token, Map.of("version", 0, "code", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void otherWorkspaceCannotReadOrTouchSnippets() {
		String owner = signupToken();
		String intruder = signupToken();
		String id = createSnippet(owner, "Private", "java", "secret-ish code");

		for (String path : List.of("/api/v1/snippets/" + id, "/api/v1/snippets/" + id + "/versions",
				"/api/v1/snippets/" + id + "/versions/1")) {
			assertThat(call(HttpMethod.GET, path, intruder, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}
		assertThat(call(HttpMethod.PATCH, "/api/v1/snippets/" + id, intruder, Map.of("version", 0, "code", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.POST, "/api/v1/snippets/" + id + "/usage", intruder, Map.of("action", "COPY"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ids(intruder, "/api/v1/snippets")).isEmpty();
		var detail = call(HttpMethod.GET, "/api/v1/snippets/" + id, owner, null);
		assertThat((Integer) JsonPath.read(detail.getBody(), "$.snippet.currentVersionNo")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(detail.getBody(), "$.snippet.useCount")).isZero();
	}

	// ---- usage / activity / dangerous content ----------------------------------------------

	@Test
	void usageCountsAreAtomicUnderConcurrentCopies() throws Exception {
		String token = signupToken();
		String id = createSnippet(token, "Usage", "java", "x");
		assertThat(call(HttpMethod.POST, "/api/v1/snippets/" + id + "/usage", token, Map.of("action", "DELETE"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		int copies = 8;
		ExecutorService pool = Executors.newFixedThreadPool(copies);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < copies; i++) {
			results.add(pool.submit(() -> call(HttpMethod.POST, "/api/v1/snippets/" + id + "/usage", token,
					Map.of("action", "COPY")).getStatusCode().value()));
		}
		for (Future<Integer> f : results) {
			assertThat(f.get()).isEqualTo(204);
		}
		pool.shutdown();

		var detail = call(HttpMethod.GET, "/api/v1/snippets/" + id, token, null);
		assertThat((Integer) JsonPath.read(detail.getBody(), "$.snippet.useCount")).isEqualTo(copies);
		assertThat((String) JsonPath.read(detail.getBody(), "$.snippet.lastUsedAt")).isNotBlank();
		// 사용 기록은 Node version을 올리지 않는다(동시 편집 충돌 원인이 되면 안 된다).
		assertThat((Integer) JsonPath.read(detail.getBody(), "$.version")).isZero();
	}

	@Test
	void activityLogRecordsCreationUpdateAndVersionCreation() throws InterruptedException {
		String token = signupToken();
		String id = createSnippet(token, "Logged", "java", "one");
		patch(token, id, 0, Map.of("code", "two"));
		patch(token, id, 1, Map.of("title", "Logged 2"));

		UUID nodeId = UUID.fromString(id);
		for (int i = 0; i < 50; i++) { // 활동 기록은 커밋 이후 비동기에 가까운 AFTER_COMMIT
			Integer count = jdbc.queryForObject("select count(*) from activity_logs where object_id = ?",
					Integer.class, nodeId);
			if (count != null && count >= 4) {
				break;
			}
			Thread.sleep(100);
		}
		List<String> actions = jdbc.queryForList(
				"select action from activity_logs where object_id = ? order by created_at, action", String.class, nodeId);
		assertThat(actions).containsExactlyInAnyOrder("SNIPPET_CREATED", "SNIPPET_UPDATED", "SNIPPET_VERSION_CREATED",
				"SNIPPET_UPDATED");
	}

	@Test
	void dangerousContentIsStoredVerbatimAndServedOnlyAsJson() {
		String token = signupToken();
		String code = "<script>alert('xss')</script>\n<img src=x onerror=alert(1)>\n`rm -rf /`";
		String id = createSnippet(token, "Dangerous", "html", code);

		var response = call(HttpMethod.GET, "/api/v1/snippets/" + id, token, null);
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
		assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat((String) JsonPath.read(response.getBody(), "$.currentVersion.code")).isEqualTo(code);
	}

	@Test
	void schemaKeepsSnippetRowsConsistentWithNodeType() {
		String token = signupToken();
		createSnippet(token, "Consistency", "java", "x");
		// §12.3 정합성 검사 SQL: 결과가 0이 아니면 배포를 막는다.
		Integer mismatched = jdbc.queryForObject("""
				select count(*) from snippets s join knowledge_nodes n on n.id = s.node_id
				where n.node_type <> 'SNIPPET'""", Integer.class);
		Integer orphanNodes = jdbc.queryForObject("""
				select count(*) from knowledge_nodes n
				where n.node_type = 'SNIPPET' and not exists (select 1 from snippets s where s.node_id = n.id)""",
				Integer.class);
		assertThat(mismatched).isZero();
		assertThat(orphanNodes).isZero();
	}

	// ---- helpers ----------------------------------------------------------------------------

	private Map<String, Object> snippet(String title, String language, String code) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("language", language);
		body.put("code", code);
		return body;
	}

	private String createSnippet(String token, String title, String language, String code) {
		var response = call(HttpMethod.POST, "/api/v1/snippets", token, snippet(title, language, code));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private ResponseEntity<String> patch(String token, String id, int version, Map<String, Object> changes) {
		Map<String, Object> body = new HashMap<>(changes);
		body.put("version", version);
		var response = call(HttpMethod.PATCH, "/api/v1/snippets/" + id, token, body);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return response;
	}

	private List<String> ids(String token, String path) {
		return JsonPath.read(call(HttpMethod.GET, path, token, null).getBody(), "$.items[*].id");
	}

	private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(baseUrl(path), method, new HttpEntity<>(body, headers), String.class);
	}

	private String signupToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(Map.of(
				"email", "s-" + UUID.randomUUID() + "@example.com", "displayName", "Snippet Tester",
				"password", "correct-horse-battery"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
