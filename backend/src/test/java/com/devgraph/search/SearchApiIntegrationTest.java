package com.devgraph.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * 설계서 §19 Phase 5 테스트: 랭킹 fixture, 한국어/영어/코드 symbol, workspace isolation, cursor 계약,
 * highlight 계약, 쿼리 플랜(explain) 검증. 완료 조건: 제목 exact가 본문 match보다 우선하고, 정해진 dataset에서
 * 대표 20개 query의 기대 상위 결과가 회귀 테스트를 통과한다.
 */
class SearchApiIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MeterRegistry meterRegistry;

	/** 정해진 dataset. 제목 → id. */
	private final Map<String, String> ids = new LinkedHashMap<>();

	// ---- ranking fixture: 대표 20개 query ------------------------------------------------------

	@Test
	void representativeQueriesReturnTheExpectedTopResults() {
		String token = signupToken();
		buildFixture(token);

		// 1. 제목 exact가 본문·요약·다른 제목 일치보다 우선한다(완료 조건).
		assertTop(token, "JWT", "JWT");
		// 2. exact 제목 vs 같은 접두어의 더 긴 제목
		assertTop(token, "jwt authentication", "JWT Authentication");
		// 3. 코드 symbol (Snippet 현재 코드)
		assertTop(token, "JwtAuthenticationFilter", "JWT Authentication Filter");
		// 4. 한국어 제목 부분 일치
		assertTop(token, "격리", "Transaction 격리 수준");
		// 5. 제목 접두어가 다른 문서의 본문 일치보다 위
		assertTopN(token, "fetch join", "Fetch join query", "N+1 문제");
		// 6. 태그 exact
		assertTop(token, "devops", "Docker Compose");
		// 7. 접두어가 같은 두 항목은 상위 2개 안에 모두
		assertTopSet(token, "docker", 2, "Docker Compose", "Docker compose file");
		// 8. 코드 symbol 소문자
		assertTop(token, "retrywithbackoff", "Retry with backoff");
		// 9. 언어 exact → 해당 언어의 Snippet 3개(동점이라 순서는 최근 수정 순)
		assertTopSet(token, "java", 3, "JWT Authentication Filter", "Fetch join query", "Rotating token helper");
		assertThat(titles(search(token, "java", ""))).hasSize(3);
		// 10. 영어 제목 접두어
		assertTop(token, "kubernetes", "Kubernetes Basics");
		// 11. 제목 토큰 일치가 본문 일치보다 위
		assertTop(token, "cache", "Redis Cache Aside");
		// 12. 숫자가 섞인 이름
		assertTop(token, "oauth2", "OAuth2 Authorization Code");
		// 13. 특수문자가 섞인 제목 (LIKE/tsquery 특수문자 안전)
		assertTop(token, "n+1", "N+1 문제");
		// 14. 한국어 두 단어 제목 일부
		assertTop(token, "격리 수준", "Transaction 격리 수준");
		// 15. exact 제목(두 단어)
		assertTop(token, "http caching", "HTTP Caching");
		// 16. 본문 FTS
		assertTop(token, "committed", "Transaction 격리 수준");
		// 17. 다단어 AND 본문 FTS
		assertTop(token, "filter chain", "Spring Security");
		// 18. 현재 버전 코드만 대상
		assertTop(token, "modernToken", "Rotating token helper");
		// 19. 과거 버전 코드는 결과에 나오지 않는다
		assertThat(search(token, "legacyToken", "").hits()).isEmpty();
		// 20. 한국어 본문의 완전한 토큰
		assertTop(token, "논의", "회의 메모");
	}

	@Test
	void exactTitleOutranksBodyAndSummaryMatchesAndTheOrderIsExplainable() {
		String token = signupToken();
		buildFixture(token);

		var titles = titles(search(token, "JWT", ""));
		assertThat(titles.get(0)).isEqualTo("JWT");
		// 본문·요약에만 JWT가 있는 항목은 제목 일치 항목들 뒤에 온다.
		assertThat(titles.indexOf("회의 메모")).isGreaterThan(titles.indexOf("JWT Authentication Filter"));
		assertThat(titles.indexOf("Spring Security")).isGreaterThan(titles.indexOf("JWT Authentication"));

		var first = search(token, "JWT", "").hit(0);
		assertThat((List<String>) JsonPath.read(first, "$.matchedFields")).contains("title");
		assertThat((Double) JsonPath.read(first, "$.score")).isGreaterThan(100.0);
	}

	@Test
	void favoriteBreaksTiesBetweenOtherwiseEqualMatches() {
		String token = signupToken();
		String one = concept(token, "Alpha One");
		concept(token, "Alpha Two");
		assertThat(call(HttpMethod.PUT, "/api/v1/nodes/" + one + "/favorite", token, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		var hits = search(token, "alpha", "");
		assertThat(titles(hits)).containsExactly("Alpha One", "Alpha Two");
		assertThat((Boolean) JsonPath.read(hits.hit(0), "$.favorite")).isTrue();
	}

	@Test
	void koreanBodySubstringIsNotSearchableYet() {
		String token = signupToken();
		call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "NOTE", "title", "메모", "bodyMd", "JWT 만료시간을 논의했다"));
		// 'simple' 설정은 형태소 분석이 없어 본문은 공백으로 나뉜 토큰 단위로만 일치한다(제목은 trigram으로 부분 일치).
		// 조사가 붙은 한국어 본문의 부분 문자열 검색은 아직 지원하지 않는다 — 설계서 §15.4 한계.
		// 이 검증은 한계를 명시하기 위한 것이며, 본문 trigram을 도입하면 기대값을 바꾼다.
		assertThat(search(token, "만료시간을", "").hits()).hasSize(1);
		assertThat(search(token, "만료시간", "").hits()).isEmpty();
	}

	// ---- validation --------------------------------------------------------------------------

	@Test
	void tooShortOrJamoOnlyQueriesAreRejectedButTwoCharactersAreFine() {
		String token = signupToken();
		for (String q : new String[] { "", "a", "  a  ", "ㅅ", "ㅅㅅ", "ㄱ ㄴ" }) {
			var response = call(HttpMethod.GET, "/api/v1/search?q=" + enc(q), token, null);
			assertThat(response.getStatusCode()).as("q=[" + q + "]").isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(response.getBody()).contains("QUERY_TOO_SHORT");
		}
		assertThat(call(HttpMethod.GET, "/api/v1/search", token, null).getBody()).contains("QUERY_TOO_SHORT");
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=ab", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=" + enc("가나"), token, null).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=" + enc("a".repeat(201)), token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=ab&size=101", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=ab&cursor=bad", token, null).getBody())
				.contains("INVALID_CURSOR");
		assertThat(restTemplate.getForEntity(baseUrl("/api/v1/search?q=ab"), String.class).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void hostileInputNeverBreaksTheQueryOrTheDatabase() {
		String token = signupToken();
		concept(token, "Normal concept");
		concept(token, "100%_done");
		for (String q : new String[] { "'; drop table knowledge_nodes; --", "a & | ! ( b", "foo:* bar", "\"unbalanced quote",
				"a\\b", "((()))", "<script>alert(1)</script>", "%%", "__" }) {
			var response = call(HttpMethod.GET, "/api/v1/search?q=" + enc(q), token, null);
			assertThat(response.getStatusCode()).as("q=[" + q + "]").isEqualTo(HttpStatus.OK);
		}
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes", Integer.class)).isPositive();
		// LIKE 와일드카드는 문자 그대로 검색된다: "%%"는 아무 제목에도 없다.
		assertThat(search(token, "%%", "").hits()).isEmpty();
		assertThat(titles(search(token, "100%", ""))).containsExactly("100%_done");
		assertThat(search(token, "10_", "").hits()).isEmpty();
	}

	// ---- isolation / filters / status ---------------------------------------------------------

	@Test
	void otherWorkspacesResultsAndFallbackNeverLeak() {
		String owner = signupToken();
		String other = signupToken();
		concept(owner, "Secret roadmap");
		snippet(owner, "Secret snippet", "java", "class SecretToken {}");

		for (String q : new String[] { "secret", "roadmap", "SecretToken", "java" }) {
			var hits = search(other, q, "");
			assertThat(hits.hits()).as(q).isEmpty();
			// 0건 안내(최근 항목)에도 남의 항목이 나오지 않는다.
			assertThat(JsonPath.<List<?>>read(hits.body, "$.fallback.recent")).isEmpty();
			assertThat(JsonPath.<List<?>>read(hits.body, "$.fallback.similar")).isEmpty();
		}
		assertThat(titles(search(owner, "secret", ""))).containsExactlyInAnyOrder("Secret roadmap", "Secret snippet");
	}

	@Test
	void filtersByTypeLanguageFrameworkTagAndArchivedState() {
		String token = signupToken();
		String tagId = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", token, Map.of("name", "auth")).getBody(),
				"$.id");
		String tagged = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "CONCEPT", "title", "Widget concept", "tagIds", List.of(tagId))).getBody(), "$.id");
		concept(token, "Widget other");
		snippet(token, "Widget java", "java", "class Widget {}");
		Map<String, Object> springSnippet = new HashMap<>(
				Map.of("title", "Widget spring", "language", "java", "code", "class WidgetBean {}", "framework", "spring"));
		call(HttpMethod.POST, "/api/v1/snippets", token, springSnippet);
		String archived = concept(token, "Widget archived");
		call(HttpMethod.POST, "/api/v1/nodes/" + archived + "/archive", token, Map.of("version", 0));
		String trashed = concept(token, "Widget trashed");
		call(HttpMethod.POST, "/api/v1/nodes/" + trashed + "/trash", token, Map.of("version", 0));

		assertThat(titles(search(token, "widget", ""))).containsExactlyInAnyOrder("Widget concept", "Widget other",
				"Widget java", "Widget spring");
		assertThat(titles(search(token, "widget", "&archived=true"))).contains("Widget archived")
				.doesNotContain("Widget trashed");
		assertThat(titles(search(token, "widget", "&types=SNIPPET"))).containsExactlyInAnyOrder("Widget java",
				"Widget spring");
		assertThat(titles(search(token, "widget", "&types=CONCEPT,SNIPPET&language=java"))).containsExactlyInAnyOrder(
				"Widget java", "Widget spring");
		assertThat(titles(search(token, "widget", "&framework=Spring"))).containsExactly("Widget spring");
		assertThat(titles(search(token, "widget", "&tagId=" + tagId))).containsExactly("Widget concept");
		assertThat(tagged).isNotBlank();
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=widget&types=NOPE", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void snippetSearchUsesOnlyTheCurrentVersionAndIsCaseInsensitive() {
		String token = signupToken();
		String id = snippet(token, "Token helper", "java", "String legacyToken() { return \"a\"; }");
		assertThat(titles(search(token, "LEGACYTOKEN", ""))).containsExactly("Token helper");
		var updated = call(HttpMethod.PATCH, "/api/v1/snippets/" + id, token,
				Map.of("version", 0, "code", "String modernToken() { return \"b\"; }"));
		assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(search(token, "legacyToken", "").hits()).isEmpty();
		assertThat(titles(search(token, "moderntoken", ""))).containsExactly("Token helper");
	}

	// ---- cursor contract ---------------------------------------------------------------------
	@Test
	void cursorKeepsRecencyScoresStableAcrossADayBoundary() throws InterruptedException {
		String token = signupToken();
		String a = concept(token, "Boundary item A");
		concept(token, "Boundary item B");
		concept(token, "Boundary item C");
		UUID workspace = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class,
				UUID.fromString(a));
		jdbc.update("update knowledge_nodes set updated_at = now() - interval '1 day' + interval '2 seconds' where workspace_id = ?", workspace);
		var first = search(token, "boundary item", "&size=1");
		String cursor = JsonPath.read(first.body, "$.cursor");
		var parts = com.devgraph.common.web.CursorCodec.decode(cursor, 4);
		Thread.sleep(2500); // The score boundary passes, without changing any stored data.
		var second = search(token, "boundary item", "&size=1&cursor=" + cursor);
		assertThat(ids(second)).doesNotContainAnyElementsOf(ids(first));
		assertThat(JsonPath.<Double>read(second.hit(0), "$.score"))
				.isEqualTo(JsonPath.<Double>read(first.hit(0), "$.score"));
		String next = JsonPath.read(second.body, "$.cursor");
		assertThat(com.devgraph.common.web.CursorCodec.decode(next, 4).get(3)).isEqualTo(parts.get(3));
		var third = search(token, "boundary item", "&size=1&cursor=" + next);
		List<String> walked = new ArrayList<>(ids(first)); walked.addAll(ids(second)); walked.addAll(ids(third));
		assertThat(walked).hasSize(3).doesNotHaveDuplicates();
	}

	@Test
	void cursorPagingWalksEveryResultOnceInTheSameOrderAsOnePage() {
		String token = signupToken();
		for (int i = 10; i < 55; i++) {
			concept(token, "Page item " + i);
		}
		var all = ids(search(token, "page item", "&size=100"));
		assertThat(all).hasSize(45).doesNotHaveDuplicates();

		List<String> walked = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			var page = search(token, "page item", "&size=10" + (cursor == null ? "" : "&cursor=" + cursor));
			walked.addAll(ids(page));
			cursor = (Boolean) JsonPath.read(page.body, "$.hasMore") ? JsonPath.read(page.body, "$.cursor") : null;
			pages++;
		} while (cursor != null && pages < 20);

		assertThat(pages).isEqualTo(5);
		assertThat(walked).containsExactlyElementsOf(all);
	}

	@Test
	void rowsWithIdenticalScoreAndTimestampAreOrderedByIdAcrossPages() {
		String token = signupToken();
		String seed = concept(token, "Tie seed");
		UUID workspaceId = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class,
				UUID.fromString(seed));
		UUID userId = jdbc.queryForObject("select created_by from knowledge_nodes where id = ?", UUID.class,
				UUID.fromString(seed));
		// 제목·updated_at이 모두 같은 25개: 점수도 같으므로 id 오름차순 tie-break만이 순서를 결정한다.
		jdbc.update("""
				insert into knowledge_nodes (id, workspace_id, created_by, node_type, title, created_at, updated_at)
				select gen_random_uuid(), ?, ?, 'CONCEPT', 'Tie node', timestamptz '2020-01-01 00:00:00+00',
				       timestamptz '2020-01-01 00:00:00+00' from generate_series(1, 25)""", workspaceId, userId);

		List<String> walked = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			var page = search(token, "tie node", "&size=7" + (cursor == null ? "" : "&cursor=" + cursor));
			walked.addAll(ids(page));
			cursor = (Boolean) JsonPath.read(page.body, "$.hasMore") ? JsonPath.read(page.body, "$.cursor") : null;
			pages++;
		} while (cursor != null && pages < 20);

		assertThat(walked).hasSize(25).doesNotHaveDuplicates();
		List<String> sorted = new ArrayList<>(walked);
		sorted.sort(String::compareTo);
		assertThat(walked).containsExactlyElementsOf(sorted);
	}

	// ---- highlight contract ------------------------------------------------------------------

	@Test
	void highlightIsAStructuredSegmentArrayWithVerbatimText() {
		String token = signupToken();
		String code = "<b>public</b> class JwtAuthenticationFilter { /* <script>alert(1)</script> */ }";
		snippet(token, "JWT Authentication Filter", "java", code);

		var hit = search(token, "jwt", "").hit(0);
		List<String> titleTexts = JsonPath.read(hit, "$.highlight.title[*].text");
		List<Boolean> titleMatched = JsonPath.read(hit, "$.highlight.title[*].matched");
		assertThat(titleTexts).containsExactly("JWT", " Authentication Filter");
		assertThat(titleMatched).containsExactly(true, false);

		// 코드 발췌는 원문 그대로다(escape·HTML 변환 없음). 일치 구간만 matched=true.
		var codeHit = search(token, "JwtAuthenticationFilter", "").hit(0);
		List<String> codeTexts = JsonPath.read(codeHit, "$.highlight.code[*].text");
		assertThat(String.join("", codeTexts)).isEqualTo(code);
		assertThat(JsonPath.<List<String>>read(codeHit, "$.highlight.code[?(@.matched==true)].text"))
				.containsExactly("JwtAuthenticationFilter");
		assertThat((List<String>) JsonPath.read(codeHit, "$.matchedFields")).contains("code");
		assertThat((String) JsonPath.read(codeHit, "$.language")).isEqualTo("java");
	}

	@Test
	void longBodiesAreExcerptedAroundTheMatchWithEllipsis() {
		String token = signupToken();
		String body = "앞부분 ".repeat(200) + "TARGETWORD 가 여기 있다 " + "뒷부분 ".repeat(200);
		call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "NOTE", "title", "Long note", "bodyMd", body));

		var hit = search(token, "targetword", "").hit(0);
		List<String> texts = JsonPath.read(hit, "$.highlight.body[*].text");
		assertThat(texts.get(0)).isEqualTo("…");
		assertThat(texts.get(texts.size() - 1)).isEqualTo("…");
		assertThat(String.join("", texts).length()).isLessThan(400);
		assertThat(JsonPath.<List<String>>read(hit, "$.highlight.body[?(@.matched==true)].text"))
				.containsExactly("TARGETWORD");
	}

	@Test
	void aMegabyteBodyCanBeSavedAndOnlyItsIndexedPrefixIsFullTextSearchable() {
		String token = signupToken();
		String body = "earlyword " + "filler ".repeat(140_000) + "verylatewordzzz";
		assertThat(body.length()).isGreaterThan(900_000);
		var created = call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "NOTE", "title", "Huge", "bodyMd", body));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED); // tsvector 1MB 한계에 걸리지 않는다
		assertThat(titles(search(token, "earlyword", ""))).containsExactly("Huge");
		// 앞 100,000자 뒤의 내용은 색인되지 않는다(설계서에 한계로 기록).
		assertThat(search(token, "verylatewordzzz", "").hits()).isEmpty();
	}

	// ---- zero-result fallback -----------------------------------------------------------------

	@Test
	void zeroResultsExplainFiltersSimilarTitlesAndRecentItems() throws InterruptedException {
		String token = signupToken();
		concept(token, "Kubernetes Basics");
		Thread.sleep(5);
		concept(token, "Docker Compose");

		// 필터가 원인: 필터를 풀면 몇 건인지 알려 준다.
		var filtered = search(token, "docker", "&types=SNIPPET");
		assertThat(filtered.hits()).isEmpty();
		assertThat((Integer) JsonPath.read(filtered.body, "$.fallback.unfilteredCount")).isEqualTo(1);

		// 오타: 완화된 trigram으로 유사 결과를 준다.
		var typo = search(token, "kubernetis", "");
		assertThat(typo.hits()).isEmpty();
		assertThat(JsonPath.<List<String>>read(typo.body, "$.fallback.similar[*].title")).contains("Kubernetes Basics");
		assertThat((Object) JsonPath.read(typo.body, "$.fallback.unfilteredCount")).isNull();
		assertThat(JsonPath.<List<?>>read(typo.body, "$.fallback.recent")).isEmpty();

		// 유사한 것도 없으면 최근 항목을 제안한다(최근 수정 순).
		var nothing = search(token, "zzzqqq", "");
		assertThat(JsonPath.<List<String>>read(nothing.body, "$.fallback.recent[*].title"))
				.containsExactly("Docker Compose", "Kubernetes Basics");
		// 결과가 있으면 fallback은 없다.
		assertThat((Object) JsonPath.read(search(token, "docker", "").body, "$.fallback")).isNull();
	}

	// ---- query plans / metrics ----------------------------------------------------------------

	@Test
	void indexesExistAndThePlannerCanUseThemForEachSearchPredicate() {
		signupToken();
		// 데이터가 적으면 planner가 순차 스캔을 고르므로, 인덱스를 쓸 수 있는지(=조건이 인덱스 가능한 형태인지)만 확인한다.
		assertThat(plan("select id from knowledge_nodes where search_vector @@ websearch_to_tsquery('simple', 'jwt')"))
				.contains("ix_knowledge_nodes__search_vector");
		assertThat(plan("select id from knowledge_nodes where lower(title) like '%jwt%' escape '!'"))
				.contains("ix_knowledge_nodes__title_trgm");
		assertThat(plan("select id from knowledge_nodes where lower(title) like 'jwt%' escape '!'"))
				.contains("ix_knowledge_nodes__title_trgm");
		assertThat(plan("select id from snippet_versions where code ilike '%jwt%' escape '!'"))
				.contains("ix_snippet_versions__code_trgm");
		assertThat(plan("select id from tags where workspace_id = gen_random_uuid() and normalized_name = 'jwt'"))
				.contains("uq_tags__workspace_name");
	}

	@Test
	void queryMetricsAreRecordedWithoutTheQueryText() {
		String token = signupToken();
		concept(token, "Metric concept");
		long before = meterRegistry.find("devgraph.search.duration").timers().stream().mapToLong(t -> t.count()).sum();
		search(token, "metric", "");
		search(token, "nothingmatches", "");
		long after = meterRegistry.find("devgraph.search.duration").timers().stream().mapToLong(t -> t.count()).sum();
		assertThat(after - before).isEqualTo(2);
		assertThat(meterRegistry.find("devgraph.search.zero_results").counter()).isNotNull();
		// 지표 태그에는 검색어가 없다.
		meterRegistry.find("devgraph.search.duration").timers().forEach(timer -> timer.getId().getTags()
				.forEach(tag -> assertThat(tag.getValue()).isIn("hit", "zero")));
	}

	// ---- fixture & helpers -------------------------------------------------------------------

	private void buildFixture(String token) {
		put("JWT", concept(token, "JWT", "JSON Web Token 인증", null));
		put("JWT Authentication", concept(token, "JWT Authentication", null, "access token 과 refresh token 흐름"));
		put("Spring Security", concept(token, "Spring Security", null, "filter chain 기반이며 JWT 검증 필터를 둔다"));
		put("Transaction 격리 수준", concept(token, "Transaction 격리 수준", null, "READ COMMITTED 와 REPEATABLE READ"));
		put("Redis Cache Aside", concept(token, "Redis Cache Aside", null, "cache 무효화 전략"));
		put("Kubernetes Basics", concept(token, "Kubernetes Basics", null, null));
		put("OAuth2 Authorization Code", concept(token, "OAuth2 Authorization Code", null, null));
		put("HTTP Caching", concept(token, "HTTP Caching", null, "ETag 와 Cache-Control 헤더"));
		put("N+1 문제", note(token, "N+1 문제", "지연 로딩 때문에 fetch join 으로 해결한다"));
		put("회의 메모", note(token, "회의 메모", "JWT 만료 시간을 논의"));
		String docker = note(token, "Docker Compose", "컨테이너 여러 개를 묶는다");
		put("Docker Compose", docker);
		String tagId = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", token, Map.of("name", "devops")).getBody(),
				"$.id");
		call(HttpMethod.PATCH, "/api/v1/nodes/" + docker, token, Map.of("version", 0, "tagIds", List.of(tagId)));
		put("JWT Authentication Filter", snippet(token, "JWT Authentication Filter", "java",
				"public class JwtAuthenticationFilter extends OncePerRequestFilter {}"));
		put("Retry with backoff", snippet(token, "Retry with backoff", "typescript",
				"async function retryWithBackoff(fn) { return fn() }"));
		put("Fetch join query", snippet(token, "Fetch join query", "java", "select o from Order o join fetch o.items"));
		put("Docker compose file", snippet(token, "Docker compose file", "yaml", "services:\n  web:\n    image: nginx"));
		String rotating = snippet(token, "Rotating token helper", "java", "String legacyToken() { return \"a\"; }");
		put("Rotating token helper", rotating);
		call(HttpMethod.PATCH, "/api/v1/snippets/" + rotating, token,
				Map.of("version", 0, "code", "String modernToken() { return \"b\"; }"));
	}

	private void put(String title, String id) {
		ids.put(title, id);
	}

	private void assertTop(String token, String query, String expectedTitle) {
		var titles = titles(search(token, query, ""));
		assertThat(titles).as("query [" + query + "]").isNotEmpty();
		assertThat(titles.get(0)).as("top result for [" + query + "] in " + titles).isEqualTo(expectedTitle);
	}

	private void assertTopN(String token, String query, String first, String second) {
		var titles = titles(search(token, query, ""));
		assertThat(titles.subList(0, Math.min(2, titles.size()))).as("query [" + query + "] in " + titles)
				.containsExactly(first, second);
	}

	private void assertTopSet(String token, String query, int n, String... expected) {
		var titles = titles(search(token, query, ""));
		assertThat(titles.subList(0, Math.min(n, titles.size()))).as("query [" + query + "] in " + titles)
				.containsExactlyInAnyOrder(expected);
	}

	private String plan(String sql) {
		return jdbc.execute((ConnectionCallback<String>) connection -> {
			try (var statement = connection.createStatement()) {
				statement.execute("set enable_seqscan = off");
				statement.execute("set enable_bitmapscan = on");
				var rs = statement.executeQuery("explain " + sql);
				StringBuilder plan = new StringBuilder();
				while (rs.next()) {
					plan.append(rs.getString(1)).append('\n');
				}
				statement.execute("reset enable_seqscan");
				return plan.toString();
			}
		});
	}

	/** 응답 본문과 몇 가지 접근 도우미. */
	private record Result(String body) {

		List<?> hits() {
			return JsonPath.read(body, "$.items");
		}

		String hit(int index) {
			return JsonPath.parse((Object) JsonPath.read(body, "$.items[" + index + "]")).jsonString();
		}
	}

	private Result search(String token, String q, String extraQuery) {
		var response = call(HttpMethod.GET, "/api/v1/search?q=" + enc(q) + extraQuery, token, null);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return new Result(response.getBody());
	}

	private List<String> titles(Result result) {
		return JsonPath.read(result.body, "$.items[*].title");
	}

	private List<String> ids(Result result) {
		return JsonPath.read(result.body, "$.items[*].id");
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String concept(String token, String title) {
		return concept(token, title, null, null);
	}

	private String concept(String token, String title, String summary, String body) {
		Map<String, Object> request = new HashMap<>(Map.of("type", "CONCEPT", "title", title));
		if (summary != null) {
			request.put("summary", summary);
		}
		if (body != null) {
			request.put("bodyMd", body);
		}
		var response = call(HttpMethod.POST, "/api/v1/nodes", token, request);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String note(String token, String title, String body) {
		var response = call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "NOTE", "title", title, "bodyMd", body));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String snippet(String token, String title, String language, String code) {
		var response = call(HttpMethod.POST, "/api/v1/snippets", token,
				Map.of("title", title, "language", language, "code", code));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(java.net.URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers),
				String.class);
	}

	private String signupToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(Map.of(
				"email", "q-" + UUID.randomUUID() + "@example.com", "displayName", "Search Tester",
				"password", "correct-horse-battery"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
