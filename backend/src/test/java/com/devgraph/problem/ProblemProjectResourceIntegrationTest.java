package com.devgraph.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 설계서 §19 Phase 6 테스트: status transition, subtype invariant, 체인(원인→해결→코드→프로젝트)과
 * 전체 완료 workflow(§27.1) API 버전. Project·Resource·Error·Solution의 검증과 격리.
 */
class ProblemProjectResourceIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	private final Map<String, Map<String, String>> typeIds = new HashMap<>();

	// ---- Error --------------------------------------------------------------------------------

	@Test
	void errorLifecycleCreateUpdateOptimisticLockAndCommonEndpoints() {
		String token = signupToken();
		var created = call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "LazyInitializationException",
				"errorMessage", "could not initialize proxy - no Session", "environment", "Spring Boot 3, Hibernate 6",
				"reproductionStepsMd", "1. 컨트롤러에서 연관 접근", "causeHypothesisMd", "트랜잭션 밖 지연 로딩"));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String id = JsonPath.read(created.getBody(), "$.id");
		assertThat((String) JsonPath.read(created.getBody(), "$.type")).isEqualTo("ERROR");
		assertThat((String) JsonPath.read(created.getBody(), "$.error.resolutionStatus")).isEqualTo("OPEN");
		assertThat((Object) JsonPath.read(created.getBody(), "$.error.resolvedAt")).isNull();
		assertThat((String) JsonPath.read(created.getBody(), "$.error.occurredAt")).isNotBlank();
		assertThat(JsonPath.<List<?>>read(created.getBody(), "$.warnings")).isEmpty();

		// 부분 수정: 바뀐 것이 없으면 version 유지, 바뀌면 +1, 선택 텍스트의 빈 문자열은 지움
		var noop = call(HttpMethod.PATCH, "/api/v1/errors/" + id, token,
				Map.of("version", 0, "errorMessage", "could not initialize proxy - no Session"));
		assertThat((Integer) JsonPath.read(noop.getBody(), "$.version")).isZero();
		var changed = call(HttpMethod.PATCH, "/api/v1/errors/" + id, token,
				Map.of("version", 0, "environment", "", "causeHypothesisMd", "Open-in-view 꺼짐"));
		assertThat((Integer) JsonPath.read(changed.getBody(), "$.version")).isEqualTo(1);
		assertThat((Object) JsonPath.read(changed.getBody(), "$.error.environment")).isNull();
		assertThat((String) JsonPath.read(changed.getBody(), "$.error.causeHypothesisMd")).isEqualTo("Open-in-view 꺼짐");
		assertThat((String) JsonPath.read(changed.getBody(), "$.error.errorMessage")).contains("no Session");

		var stale = call(HttpMethod.PATCH, "/api/v1/errors/" + id, token, Map.of("version", 0, "errorMessage", "x"));
		assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(stale.getBody()).contains("VERSION_CONFLICT");

		// 상태 전이·즐겨찾기는 Node 공통 endpoint. 보관하면 기본 목록에서 빠진다.
		assertThat(call(HttpMethod.PUT, "/api/v1/nodes/" + id + "/favorite", token, null).getStatusCode().value())
				.isEqualTo(204);
		assertThat(call(HttpMethod.POST, "/api/v1/nodes/" + id + "/archive", token, Map.of("version", 1))
				.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ids(token, "/api/v1/errors")).isEmpty();
		assertThat(ids(token, "/api/v1/errors?status=ARCHIVED")).containsExactly(id);

		// Node 공통 수정/생성 경로로는 subtype 데이터를 우회할 수 없다.
		var viaNodeApi = call(HttpMethod.PATCH, "/api/v1/nodes/" + id, token, Map.of("version", 2, "title", "x"));
		assertThat(viaNodeApi.getBody()).contains("USE_SUBTYPE_API");
		assertThat(call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "ERROR", "title", "x")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void errorValidationRejectsBlankMessageOversizedFieldsAndBadInput() {
		String token = signupToken();
		assertThat(call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "t", "errorMessage", "   "))
				.getBody()).contains("errorMessage");
		assertThat(call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "t")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "", "errorMessage", "m"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		var big = call(HttpMethod.POST, "/api/v1/errors", token,
				Map.of("title", "t", "errorMessage", "a".repeat(100_001)));
		assertThat(big.getStatusCode().value()).isEqualTo(413);
		assertThat(call(HttpMethod.POST, "/api/v1/errors", token,
				Map.of("title", "t", "errorMessage", "m", "environment", "e".repeat(501))).getStatusCode().value())
				.isEqualTo(413);
		assertThat(call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "t", "errorMessage", "a\u0000b"))
				.getBody()).contains("INVALID_CHARACTER");
		// 스택 트레이스처럼 긴 메시지는 저장·조회된다.
		String trace = "java.lang.IllegalStateException: boom\n\tat com.example.A.run(A.java:1)\n".repeat(500);
		var ok = call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "trace", "errorMessage", trace));
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(ok.getBody(), "$.error.errorMessage")).isEqualTo(trace.strip());
	}

	// ---- status transitions (ERR-02) ----------------------------------------------------------

	@Test
	void statusTransitionsFollowTheStateMachineAndWarnWithoutBlockingWhenNoSolutionIsLinked() {
		String token = signupToken();
		String error = createError(token, "Err", "message");
		var inv = status(token, error, 0, "INVESTIGATING", null);
		assertThat((String) JsonPath.read(inv.getBody(), "$.error.resolutionStatus")).isEqualTo("INVESTIGATING");
		assertThat((Integer) JsonPath.read(inv.getBody(), "$.version")).isEqualTo(1);

		// 같은 상태로의 요청은 아무것도 바꾸지 않는다.
		var same = status(token, error, 1, "INVESTIGATING", null);
		assertThat((Integer) JsonPath.read(same.getBody(), "$.version")).isEqualTo(1);

		// Solution 연결 없이 RESOLVED: 저장되지만 경고한다(§9.7).
		var resolved = status(token, error, 1, "RESOLVED", null);
		assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(resolved.getBody(), "$.error.resolutionStatus")).isEqualTo("RESOLVED");
		assertThat((String) JsonPath.read(resolved.getBody(), "$.error.resolvedAt")).isNotBlank();
		assertThat(JsonPath.<List<String>>read(resolved.getBody(), "$.warnings")).containsExactly("NO_SOLUTION_LINKED");
		// 조회에서도 경고가 유지된다.
		assertThat(JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/errors/" + error, token, null).getBody(),
				"$.warnings")).containsExactly("NO_SOLUTION_LINKED");

		// 종료 상태끼리는 바로 오갈 수 없다. OPEN으로 재오픈하면 resolvedAt이 지워진다.
		var direct = status(token, error, 2, "WONT_FIX", null);
		assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(direct.getBody()).contains("INVALID_STATUS_TRANSITION");
		var reopened = status(token, error, 2, "OPEN", null);
		assertThat((Object) JsonPath.read(reopened.getBody(), "$.error.resolvedAt")).isNull();
		assertThat(JsonPath.<List<?>>read(reopened.getBody(), "$.warnings")).isEmpty();

		// Solution을 연결하면 RESOLVED 경고가 사라진다.
		String solution = createSolution(token, "Fix", "fetch join", null);
		assertThat(createRelation(token, error, solution, "SOLVED_BY").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		var resolvedWithSolution = status(token, error, 3, "RESOLVED", null);
		assertThat(JsonPath.<List<?>>read(resolvedWithSolution.getBody(), "$.warnings")).isEmpty();

		// 낡은 version은 409, 잘못된 값은 400
		assertThat(status(token, error, 0, "OPEN", null).getBody()).contains("VERSION_CONFLICT");
		assertThat(call(HttpMethod.PATCH, "/api/v1/errors/" + error + "/status", token,
				Map.of("version", 4, "status", "NOPE")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void resolvedAtCannotPrecedeOccurrenceAndWontFixKeepsNoResolvedAt() {
		String token = signupToken();
		var created = call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", "Err", "errorMessage", "m",
				"occurredAt", "2026-05-01T00:00:00Z"));
		String id = JsonPath.read(created.getBody(), "$.id");

		var before = status(token, id, 0, "RESOLVED", "2026-04-01T00:00:00Z");
		assertThat(before.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(before.getBody()).contains("BEFORE_OCCURRED_AT");
		var explicit = status(token, id, 0, "RESOLVED", "2026-05-02T10:00:00Z");
		assertThat((String) JsonPath.read(explicit.getBody(), "$.error.resolvedAt")).startsWith("2026-05-02T10:00:00");
		var reopen = status(token, id, 1, "OPEN", null);
		var wontFix = status(token, id, 2, "WONT_FIX", "2026-05-03T00:00:00Z");
		assertThat((String) JsonPath.read(wontFix.getBody(), "$.error.resolutionStatus")).isEqualTo("WONT_FIX");
		assertThat((Object) JsonPath.read(wontFix.getBody(), "$.error.resolvedAt")).isNull(); // RESOLVED가 아니면 무시
		assertThat(reopen.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void trashedErrorsCannotChangeStatusAndOtherWorkspacesCannotTouchThem() {
		String owner = signupToken();
		String intruder = signupToken();
		String error = createError(owner, "Private err", "secret message");
		assertThat(call(HttpMethod.GET, "/api/v1/errors/" + error, intruder, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(status(intruder, error, 0, "RESOLVED", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PATCH, "/api/v1/errors/" + error, intruder, Map.of("version", 0, "errorMessage", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ids(intruder, "/api/v1/errors")).isEmpty();

		call(HttpMethod.POST, "/api/v1/nodes/" + error + "/trash", owner, Map.of("version", 0));
		var changed = status(owner, error, 1, "RESOLVED", null);
		assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(changed.getBody()).contains("INVALID_NODE_STATE");
		// Concept id를 Error API로 열 수 없다.
		String concept = concept(owner, "A concept");
		assertThat(call(HttpMethod.GET, "/api/v1/errors/" + concept, owner, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---- Solution ----------------------------------------------------------------------------

	@Test
	void solutionCreationWithErrorLinkIsAtomicAndValidated() {
		String token = signupToken();
		String other = signupToken();
		String error = createError(token, "Err", "message");

		var created = call(HttpMethod.POST, "/api/v1/solutions", token, Map.of("title", "Fetch join",
				"approachMd", "fetch join 으로 한 번에 읽는다", "stepsMd", "1. 쿼리 수정", "verificationMd", "테스트 통과",
				"tradeoffsMd", "카티션 곱 주의", "errorNodeId", error));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String solution = JsonPath.read(created.getBody(), "$.id");
		// Solution의 incoming(backlink)에 Error가 inverse label로, Error의 outgoing에 Solution이 보인다.
		assertThat((String) JsonPath.read(created.getBody(), "$.relations.incoming[0].label")).isEqualTo("solves");
		assertThat((String) JsonPath.read(created.getBody(), "$.relations.incoming[0].nodeId")).isEqualTo(error);
		var errorDetail = call(HttpMethod.GET, "/api/v1/errors/" + error, token, null).getBody();
		assertThat((String) JsonPath.read(errorDetail, "$.relations.outgoing[0].type")).isEqualTo("SOLVED_BY");
		assertThat((String) JsonPath.read(errorDetail, "$.relations.outgoing[0].nodeId")).isEqualTo(solution);

		// 검증 실패 시 Solution도 만들어지지 않는다(한 트랜잭션).
		int before = count("select count(*) from solution_records");
		String concept = concept(token, "Not an error");
		var wrongType = call(HttpMethod.POST, "/api/v1/solutions", token,
				Map.of("title", "x", "approachMd", "a", "errorNodeId", concept));
		assertThat(wrongType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(wrongType.getBody()).contains("TYPE_NOT_ALLOWED");
		var foreign = call(HttpMethod.POST, "/api/v1/solutions", other,
				Map.of("title", "x", "approachMd", "a", "errorNodeId", error));
		assertThat(foreign.getBody()).contains("INVALID_RELATION_NODE");
		assertThat(count("select count(*) from solution_records")).isEqualTo(before);
		assertThat(ids(token, "/api/v1/solutions")).containsExactly(solution);
		assertThat(ids(other, "/api/v1/solutions")).isEmpty();

		// approach는 필수, 다른 필드는 선택
		assertThat(call(HttpMethod.POST, "/api/v1/solutions", token, Map.of("title", "x")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.POST, "/api/v1/solutions", token, Map.of("title", "x", "approachMd", "  "))
				.getBody()).contains("approachMd");
		assertThat(call(HttpMethod.POST, "/api/v1/solutions", token, Map.of("title", "x", "approachMd", "a"))
				.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void solutionUpdateListFiltersAndChainCounts() {
		String token = signupToken();
		String error = createError(token, "Err", "m");
		String solution = createSolution(token, "Fix", "approach", error);
		String snippet = snippet(token, "Impl", "java", "class A {}");
		String project = createProject(token, "TeamFlow");
		createRelation(token, solution, snippet, "IMPLEMENTED_WITH");
		createRelation(token, solution, project, "APPLIED_IN");
		createRelation(token, error, project, "OCCURRED_IN");

		var updated = call(HttpMethod.PATCH, "/api/v1/solutions/" + solution, token,
				Map.of("version", 0, "verificationMd", "검증함"));
		assertThat((Integer) JsonPath.read(updated.getBody(), "$.version")).isEqualTo(1);
		assertThat(call(HttpMethod.PATCH, "/api/v1/solutions/" + solution, token, Map.of("version", 0, "approachMd", "x"))
				.getBody()).contains("VERSION_CONFLICT");

		var list = call(HttpMethod.GET, "/api/v1/solutions", token, null).getBody();
		assertThat((Integer) JsonPath.read(list, "$.items[0].errorCount")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(list, "$.items[0].snippetCount")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(list, "$.items[0].projectCount")).isEqualTo(1);
		assertThat(ids(token, "/api/v1/solutions?errorId=" + error)).containsExactly(solution);
		assertThat(ids(token, "/api/v1/solutions?projectId=" + project)).containsExactly(solution);
		assertThat(ids(token, "/api/v1/solutions?errorId=" + UUID.randomUUID())).isEmpty();

		// 체인 미리보기: Error 목록의 해결/프로젝트 수와 필터
		var errors = call(HttpMethod.GET, "/api/v1/errors", token, null).getBody();
		assertThat((Integer) JsonPath.read(errors, "$.items[0].solutionCount")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(errors, "$.items[0].projectCount")).isEqualTo(1);
		assertThat(ids(token, "/api/v1/errors?projectId=" + project)).containsExactly(error);
		assertThat(ids(token, "/api/v1/errors?resolution=RESOLVED")).isEmpty();
		assertThat(ids(token, "/api/v1/errors?resolution=OPEN,INVESTIGATING")).containsExactly(error);

		// 휴지통에 간 Solution은 해결 수에서 빠진다.
		call(HttpMethod.POST, "/api/v1/nodes/" + solution + "/trash", token, Map.of("version", 1));
		assertThat((Integer) JsonPath.read(call(HttpMethod.GET, "/api/v1/errors", token, null).getBody(),
				"$.items[0].solutionCount")).isZero();
	}

	// ---- Project -----------------------------------------------------------------------------

	@Test
	void projectCreateUpdateValidationAndListByStatus() {
		String token = signupToken();
		var created = call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", "TeamFlow", "summary", "협업 도구",
				"description", "# 개요\n실제 프로젝트", "repositoryUrl", "https://github.com/me/teamflow",
				"startedOn", "2026-01-01"));
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String id = JsonPath.read(created.getBody(), "$.id");
		assertThat((String) JsonPath.read(created.getBody(), "$.project.projectStatus")).isEqualTo("ACTIVE");
		assertThat((String) JsonPath.read(created.getBody(), "$.description")).isEqualTo("# 개요\n실제 프로젝트");
		assertThat((String) JsonPath.read(created.getBody(), "$.project.startedOn")).isEqualTo("2026-01-01");

		var updated = call(HttpMethod.PATCH, "/api/v1/projects/" + id, token, Map.of("version", 0, "projectStatus",
				"COMPLETED", "endedOn", "2026-06-30", "description", "완료"));
		assertThat(updated.getStatusCode()).as(updated.getBody()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(updated.getBody(), "$.project.projectStatus")).isEqualTo("COMPLETED");
		assertThat((String) JsonPath.read(updated.getBody(), "$.description")).isEqualTo("완료");
		assertThat((String) JsonPath.read(updated.getBody(), "$.project.repositoryUrl")).isEqualTo("https://github.com/me/teamflow");
		var cleared = call(HttpMethod.PATCH, "/api/v1/projects/" + id, token,
				Map.of("version", 1, "clearEndedOn", true, "repositoryUrl", ""));
		assertThat(cleared.getStatusCode()).as(cleared.getBody()).isEqualTo(HttpStatus.OK);
		assertThat((Object) JsonPath.read(cleared.getBody(), "$.project.endedOn")).isNull();
		assertThat((Object) JsonPath.read(cleared.getBody(), "$.project.repositoryUrl")).isNull();

		createProject(token, "Side project");
		assertThat(titles(token, "/api/v1/projects")).containsExactlyInAnyOrder("TeamFlow", "Side project");
		assertThat(titles(token, "/api/v1/projects?projectStatus=COMPLETED")).containsExactly("TeamFlow");
		assertThat(titles(token, "/api/v1/projects?projectStatus=ACTIVE,PAUSED")).containsExactly("Side project");

		// 검증: 기간 역전, 위험한/잘못된 저장소 URL, 상태 값
		assertThat(call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", "p", "startedOn", "2026-05-01",
				"endedOn", "2026-04-01")).getBody()).contains("BEFORE_STARTED_ON");
		for (String url : new String[] { "javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://x.com/a",
				"https://user:pw@github.com/a", "not a url", "https:///nohost" }) {
			assertThat(call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", "p", "repositoryUrl", url))
					.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
		}
		assertThat(call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", "p", "projectStatus", "NOPE"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		// 기간 역전은 수정에서도 막는다(기존 값과의 조합).
		assertThat(call(HttpMethod.PATCH, "/api/v1/projects/" + id, token, Map.of("version", 2, "endedOn", "2025-01-01"))
				.getBody()).contains("BEFORE_STARTED_ON");
	}

	@Test
	void projectGraphShowsTheChainAndRejectsOtherTypesAndWorkspaces() {
		String token = signupToken();
		String other = signupToken();
		String project = createProject(token, "TeamFlow");
		String concept = concept(token, "JPA");
		String snippet = snippet(token, "Repo", "java", "class Repo {}");
		String error = createError(token, "LazyInit", "no Session");
		String solution = createSolution(token, "Fetch join", "fetch join", error);
		createRelation(token, concept, project, "USED_IN");
		createRelation(token, snippet, project, "USED_IN");
		createRelation(token, error, project, "OCCURRED_IN");
		createRelation(token, solution, project, "APPLIED_IN");

		var graph = call(HttpMethod.GET, "/api/v1/projects/" + project + "/graph", token, null);
		assertThat(graph.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((Integer) JsonPath.read(graph.getBody(), "$.appliedFilters.depth")).isEqualTo(2);
		assertThat(JsonPath.<List<String>>read(graph.getBody(), "$.nodes[*].id"))
				.containsExactlyInAnyOrder(project, concept, snippet, error, solution);
		// 방향과 의미: Solution --APPLIED_IN--> Project, Error --OCCURRED_IN--> Project
		assertThat(JsonPath.<List<String>>read(graph.getBody(), "$.edges[?(@.type=='APPLIED_IN')].source"))
				.containsExactly(solution);
		assertThat(JsonPath.<List<String>>read(graph.getBody(), "$.edges[?(@.type=='OCCURRED_IN')].target"))
				.containsExactly(project);
		assertThat(JsonPath.<List<String>>read(
				call(HttpMethod.GET, "/api/v1/projects/" + project + "/graph?nodeTypes=ERROR,SOLUTION", token, null)
						.getBody(), "$.nodes[*].id")).containsExactlyInAnyOrder(project, error, solution);

		assertThat(call(HttpMethod.GET, "/api/v1/projects/" + concept + "/graph", token, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/projects/" + project + "/graph", other, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/projects/" + project + "/graph?depth=4", token, null).getBody())
				.contains("LIMIT_EXCEEDED");

		// 프로젝트 요약의 개수
		var list = call(HttpMethod.GET, "/api/v1/projects", token, null).getBody();
		assertThat((Integer) JsonPath.read(list, "$.items[0].problemCount")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(list, "$.items[0].knowledgeCount")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(list, "$.items[0].solutionCount")).isEqualTo(1);
	}

	// ---- Resource ----------------------------------------------------------------------------

	@Test
	void resourceUrlsAreValidatedNormalizedAndDuplicatesOnlyWarn() {
		String token = signupToken();
		var first = call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", "Hibernate docs",
				"url", "https://hibernate.org/orm/documentation/?b=2&a=1", "kind", "doc"));
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String firstId = JsonPath.read(first.getBody(), "$.id");
		assertThat((String) JsonPath.read(first.getBody(), "$.resource.kind")).isEqualTo("DOC");
		assertThat((String) JsonPath.read(first.getBody(), "$.resource.siteName")).isEqualTo("hibernate.org");
		assertThat(JsonPath.<List<?>>read(first.getBody(), "$.duplicates")).isEmpty();

		// 같은 문서를 다른 표기로 또 저장: 막지 않고 경고한다(같은 문서를 다른 맥락으로 기록할 수 있다).
		var second = call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", "Hibernate docs (다른 맥락)",
				"url", "HTTPS://Hibernate.org:443/orm/documentation?utm_source=news&a=1&b=2#top"));
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(JsonPath.<List<String>>read(second.getBody(), "$.duplicates[*].id")).containsExactly(firstId);
		// 원문 URL은 사용자가 쓴 그대로 보관된다.
		assertThat((String) JsonPath.read(second.getBody(), "$.resource.url"))
				.isEqualTo("HTTPS://Hibernate.org:443/orm/documentation?utm_source=news&a=1&b=2#top");
		// 상세 조회에서도 같은 경고가 나온다.
		assertThat(JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/resources/" + firstId, token, null).getBody(),
				"$.duplicates[*].title")).containsExactly("Hibernate docs (다른 맥락)");

		// URL을 다른 주소로 바꾸면 경고가 사라지고, 휴지통의 항목은 중복에서 빠진다.
		String secondId = JsonPath.read(second.getBody(), "$.id");
		var moved = call(HttpMethod.PATCH, "/api/v1/resources/" + secondId, token,
				Map.of("version", 0, "url", "https://example.com/other"));
		assertThat(JsonPath.<List<?>>read(moved.getBody(), "$.duplicates")).isEmpty();
		assertThat((String) JsonPath.read(moved.getBody(), "$.resource.siteName")).isEqualTo("example.com");

		// 위험한 scheme과 잘못된 입력은 거부한다(§17.4).
		for (String url : new String[] { "javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html,<script>", "file:///etc/passwd",
				"ftp://example.com/a", "//example.com/a", "example.com", "https://user:pass@example.com/a", "https://",
				"http://exa mple.com", "https://example.com/" + "a".repeat(2000) }) {
			var r = call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", "x", "url", url));
			assertThat(r.getStatusCode()).as(url.length() > 60 ? "long url" : url).isEqualTo(HttpStatus.BAD_REQUEST);
		}
		assertThat(call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", "x", "url", "https://a.com",
				"kind", "NOPE")).getBody()).contains("INVALID_KIND");
		assertThat(call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", "x")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);

		assertThat(titles(token, "/api/v1/resources?kind=DOC")).containsExactly("Hibernate docs");
		assertThat(titles(token, "/api/v1/resources")).hasSize(2);
		assertThat(call(HttpMethod.GET, "/api/v1/resources?kind=NOPE", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void resourcesAreIsolatedPerWorkspaceAndDuplicatesNeverCrossWorkspaces() {
		String owner = signupToken();
		String other = signupToken();
		String id = createResource(owner, "Doc", "https://example.com/doc");
		var foreignDup = call(HttpMethod.POST, "/api/v1/resources", other, Map.of("title", "Mine", "url", "https://example.com/doc"));
		assertThat(foreignDup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(JsonPath.<List<?>>read(foreignDup.getBody(), "$.duplicates")).isEmpty(); // 남의 Workspace의 URL은 드러나지 않는다
		assertThat(call(HttpMethod.GET, "/api/v1/resources/" + id, other, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PATCH, "/api/v1/resources/" + id, other, Map.of("version", 0, "url", "https://x.com"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---- relation rules for the new types -------------------------------------------------------

	@Test
	void chainRelationsAreAllowedInTheDocumentedDirectionsOnly() {
		String token = signupToken();
		String concept = concept(token, "Lazy loading");
		String error = createError(token, "Err", "m");
		String solution = createSolution(token, "Fix", "a", null);
		String snippet = snippet(token, "Code", "java", "class A {}");
		String project = createProject(token, "P");
		String resource = createResource(token, "Doc", "https://example.com/d");

		for (String[] edge : new String[][] { { error, concept, "CAUSED_BY" }, { error, solution, "SOLVED_BY" },
				{ solution, snippet, "IMPLEMENTED_WITH" }, { error, project, "OCCURRED_IN" },
				{ concept, project, "USED_IN" }, { snippet, project, "USED_IN" }, { solution, project, "APPLIED_IN" },
				{ solution, resource, "REFERENCES" }, { concept, resource, "LEARNED_FROM" },
				{ resource, project, "USED_IN" } }) {
			assertThat(createRelation(token, edge[0], edge[1], edge[2]).getStatusCode())
					.as(edge[2]).isEqualTo(HttpStatus.CREATED);
		}
		// 설계서가 막은 조합: Snippet은 APPLIED_IN의 Source가 아니다, Error는 USED_IN의 Source가 아니다.
		assertThat(createRelation(token, snippet, project, "APPLIED_IN").getBody()).contains("TYPE_NOT_ALLOWED");
		assertThat(createRelation(token, error, project, "USED_IN").getBody()).contains("TYPE_NOT_ALLOWED");
		assertThat(createRelation(token, project, error, "OCCURRED_IN").getBody()).contains("TYPE_NOT_ALLOWED");
		assertThat(createRelation(token, solution, error, "SOLVED_BY").getBody()).contains("TYPE_NOT_ALLOWED");
	}

	@Test
	void libraryListsErrorsSolutionsAndResourcesButNotSnippetsOrProjects() {
		String token = signupToken();
		String concept = concept(token, "Concept");
		String error = createError(token, "Err", "m");
		String solution = createSolution(token, "Sol", "a", null);
		String resource = createResource(token, "Res", "https://example.com/r");
		snippet(token, "Snip", "java", "class A {}");
		createProject(token, "Proj");

		assertThat(ids(token, "/api/v1/nodes")).containsExactlyInAnyOrder(concept, error, solution, resource);
		assertThat(ids(token, "/api/v1/nodes?type=ERROR")).containsExactly(error);
		assertThat(ids(token, "/api/v1/nodes?type=RESOURCE")).containsExactly(resource);
	}

	// ---- database invariants -----------------------------------------------------------------

	@Test
	void subtypeRowsStayConsistentWithNodeTypesAndDatabaseChecksHold() {
		String token = signupToken();
		String error = createError(token, "Err", "m");
		createSolution(token, "Sol", "a", null);
		createProject(token, "Proj");
		createResource(token, "Res", "https://example.com/r");

		// §12.3 정합성 검사 SQL: 결과가 0이 아니면 배포를 막는다. subtype 4종 모두.
		for (String[] check : new String[][] { { "error_records", "ERROR" }, { "solution_records", "SOLUTION" },
				{ "projects", "PROJECT" }, { "resources", "RESOURCE" } }) {
			assertThat(count("select count(*) from " + check[0] + " s join knowledge_nodes n on n.id = s.node_id"
					+ " where n.node_type <> '" + check[1] + "'")).as(check[0] + " wrong parent type").isZero();
			assertThat(count("select count(*) from knowledge_nodes n where n.node_type = '" + check[1]
					+ "' and not exists (select 1 from " + check[0] + " s where s.node_id = n.id)"))
					.as(check[0] + " orphan nodes").isZero();
		}

		UUID id = UUID.fromString(error);
		// RESOLVED일 때만 resolved_at이 있다.
		assertThatThrownBy(() -> jdbc.update(
				"update error_records set resolution_status = 'RESOLVED', resolved_at = null where node_id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_error_records__resolved_at");
		assertThatThrownBy(() -> jdbc.update(
				"update error_records set resolution_status = 'OPEN', resolved_at = now() where node_id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_error_records__resolved_at");
		assertThatThrownBy(() -> jdbc.update("update error_records set resolution_status = 'NOPE' where node_id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("update error_records set error_message = '   ' where node_id = ?", id))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_error_records__message");
		// URL scheme과 기간은 DB도 막는다(서비스 우회에 대한 최종 방어선).
		assertThatThrownBy(() -> jdbc.update("update resources set url = 'javascript:alert(1)'"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_resources__url_scheme");
		assertThatThrownBy(() -> jdbc.update("update projects set started_on = '2026-05-01', ended_on = '2026-01-01'"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_projects__period");
		assertThatThrownBy(() -> jdbc.update("update projects set repository_url = 'ftp://x.com'"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_projects__repository_url");
		// 다른 Workspace로 위장한 subtype 행은 복합 FK가 막는다.
		UUID otherWorkspace = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class,
				UUID.fromString(concept(signupToken(), "Other")));
		assertThatThrownBy(() -> jdbc.update("update error_records set workspace_id = ? where node_id = ?", otherWorkspace, id))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_error_records__knowledge_nodes");
		// Node를 지우면 subtype 행도 함께 사라진다(cascade).
		jdbc.update("delete from knowledge_nodes where id = ?", id);
		assertThat(count("select count(*) from error_records where node_id = '" + error + "'")).isZero();
	}

	// ---- 전체 완료 workflow(§27.1)의 API 버전: seed 없이 신규 계정으로 ---------------------------------

	@Test
	void theWholeCompletionWorkflowRunsOnAFreshAccountAndIsFindableThroughSearch() {
		String token = signupToken();
		// 2~5. Concept 두 개, 관계, JPA Snippet
		String boot = concept(token, "Spring Boot");
		String jpa = concept(token, "JPA");
		assertThat(createRelation(token, boot, jpa, "RELATED_TO").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String jpaSnippet = snippet(token, "JPA repository example", "java",
				"public interface OrderRepository extends JpaRepository<Order, Long> {}");
		assertThat(createRelation(token, jpaSnippet, jpa, "IS_EXAMPLE_OF").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// 6~7. Error 등록, 원인 Concept
		String error = createError(token, "LazyInitializationException",
				"org.hibernate.LazyInitializationException: could not initialize proxy - no Session");
		String lazyLoading = concept(token, "Lazy Loading");
		assertThat(createRelation(token, error, lazyLoading, "CAUSED_BY").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// 8. Solution을 Error와 한 번에 연결해 만든다
		String solution = createSolution(token, "트랜잭션 안에서 fetch join", "서비스 트랜잭션 경계 안에서 연관을 함께 읽는다", error);
		// 9. 해결 Snippet 연결
		String fixSnippet = snippet(token, "fetch join query", "java", "select o from Order o join fetch o.items");
		assertThat(createRelation(token, solution, fixSnippet, "IMPLEMENTED_WITH").getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// 10. Project와 연결
		String project = createProject(token, "TeamFlow");
		for (String[] edge : new String[][] { { jpa, project, "USED_IN" }, { error, project, "OCCURRED_IN" },
				{ solution, project, "APPLIED_IN" }, { fixSnippet, project, "USED_IN" } }) {
			assertThat(createRelation(token, edge[0], edge[1], edge[2]).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		}
		status(token, error, 0, "RESOLVED", null);

		// 11. Project Graph: 5종 이상의 Node가 방향과 의미를 유지한 채 나온다.
		var graph = call(HttpMethod.GET, "/api/v1/projects/" + project + "/graph?depth=3", token, null).getBody();
		assertThat(JsonPath.<List<String>>read(graph, "$.nodes[*].id")).contains(project, jpa, error, solution, fixSnippet,
				lazyLoading, jpaSnippet);
		assertThat(JsonPath.<List<String>>read(graph, "$.edges[?(@.type=='SOLVED_BY')].source")).containsExactly(error);
		// 문제 체인의 해결 완료: Error에 경고가 없다.
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/errors/" + error, token, null).getBody(),
				"$.warnings")).isEmpty();

		// 12. 전역 검색으로 Error(메시지 일부)와 코드 symbol에서 Snippet 상세로 도달한다.
		var byMessage = search(token, "LazyInit");
		assertThat((String) JsonPath.read(byMessage, "$.items[0].id")).isEqualTo(error);
		assertThat((List<String>) JsonPath.read(byMessage, "$.items[0].matchedFields")).contains("title");
		var byCode = search(token, "OrderRepository");
		assertThat((String) JsonPath.read(byCode, "$.items[0].id")).isEqualTo(jpaSnippet);
		// 13. 코드 복사 → 최근 사용 반영
		assertThat(call(HttpMethod.POST, "/api/v1/snippets/" + jpaSnippet + "/usage", token, Map.of("action", "COPY"))
				.getStatusCode().value()).isEqualTo(204);
		assertThat((Integer) JsonPath.read(call(HttpMethod.GET, "/api/v1/snippets/" + jpaSnippet, token, null).getBody(),
				"$.snippet.useCount")).isEqualTo(1);
	}

	// ---- search over the new subtypes ---------------------------------------------------------

	@Test
	void searchFindsErrorMessagesEnvironmentSolutionTextAndProjectDescriptions() {
		String token = signupToken();
		String error = createError(token, "Boom", "java.lang.NullPointerException: Cannot invoke \"String.length()\"");
		call(HttpMethod.PATCH, "/api/v1/errors/" + error, token,
				Map.of("version", 0, "environment", "JDK 21 on Alpine", "causeHypothesisMd", "uninitializedfield 가 원인"));
		String solution = createSolution(token, "Guard", "null 검사를 앞에 둔다 defensivecopy", null);
		String project = JsonPath.read(call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", "Tracker",
				"description", "분산 추적 플랫폼 observability")).getBody(), "$.id");

		// 메시지의 부분 문자열(예외 이름 일부)로 찾고, 일치 필드와 발췌가 error다.
		var byMessage = search(token, "NullPointer");
		assertThat((String) JsonPath.read(byMessage, "$.items[0].id")).isEqualTo(error);
		assertThat((List<String>) JsonPath.read(byMessage, "$.items[0].matchedFields")).contains("error");
		assertThat(JsonPath.<List<String>>read(byMessage, "$.items[0].highlight.error[?(@.matched==true)].text"))
				.containsExactly("NullPointer");
		// 환경·원인(전문 검색)
		assertThat((String) JsonPath.read(search(token, "alpine"), "$.items[0].id")).isEqualTo(error);
		assertThat((String) JsonPath.read(search(token, "uninitializedfield"), "$.items[0].id")).isEqualTo(error);
		assertThat((List<String>) JsonPath.read(search(token, "alpine"), "$.items[0].matchedFields")).contains("body");
		// Solution 본문, Project 설명
		assertThat((String) JsonPath.read(search(token, "defensivecopy"), "$.items[0].id")).isEqualTo(solution);
		assertThat((String) JsonPath.read(search(token, "observability"), "$.items[0].id")).isEqualTo(project);
		// 유형 필터
		assertThat(JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/search?q=null&types=SOLUTION", token, null)
				.getBody(), "$.items[*].id")).containsExactly(solution);
		// 다른 Workspace는 찾지 못한다.
		assertThat(JsonPath.<List<?>>read(search(signupToken(), "NullPointer"), "$.items")).isEmpty();
	}

	// ---- helpers ------------------------------------------------------------------------------

	private String search(String token, String q) {
		var response = call(HttpMethod.GET, "/api/v1/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), token, null);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private int count(String sql) {
		Integer value = jdbc.queryForObject(sql, Integer.class);
		return value == null ? 0 : value;
	}

	private ResponseEntity<String> status(String token, String id, int version, String status, String resolvedAt) {
		Map<String, Object> body = new HashMap<>(Map.of("version", version, "status", status));
		if (resolvedAt != null) {
			body.put("resolvedAt", resolvedAt);
		}
		return call(HttpMethod.PATCH, "/api/v1/errors/" + id + "/status", token, body);
	}

	private List<String> ids(String token, String path) {
		return JsonPath.read(call(HttpMethod.GET, path, token, null).getBody(), "$.items[*].id");
	}

	private List<String> titles(String token, String path) {
		return JsonPath.read(call(HttpMethod.GET, path, token, null).getBody(), "$.items[*].title");
	}

	private String createError(String token, String title, String message) {
		var response = call(HttpMethod.POST, "/api/v1/errors", token, Map.of("title", title, "errorMessage", message));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String createSolution(String token, String title, String approach, String errorNodeId) {
		Map<String, Object> body = new HashMap<>(Map.of("title", title, "approachMd", approach));
		if (errorNodeId != null) {
			body.put("errorNodeId", errorNodeId);
		}
		var response = call(HttpMethod.POST, "/api/v1/solutions", token, body);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String createProject(String token, String title) {
		var response = call(HttpMethod.POST, "/api/v1/projects", token, Map.of("title", title));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String createResource(String token, String title, String url) {
		var response = call(HttpMethod.POST, "/api/v1/resources", token, Map.of("title", title, "url", url));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String concept(String token, String title) {
		var response = call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "CONCEPT", "title", title));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String snippet(String token, String title, String language, String code) {
		var response = call(HttpMethod.POST, "/api/v1/snippets", token,
				Map.of("title", title, "language", language, "code", code));
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private ResponseEntity<String> createRelation(String token, String source, String target, String typeKey) {
		String typeId = typeIds.computeIfAbsent(token, t -> {
			var body = call(HttpMethod.GET, "/api/v1/relation-types", t, null).getBody();
			Map<String, String> ids = new HashMap<>();
			List<String> keys = JsonPath.read(body, "$[*].key");
			List<String> values = JsonPath.read(body, "$[*].id");
			for (int i = 0; i < keys.size(); i++) {
				ids.put(keys.get(i), values.get(i));
			}
			return ids;
		}).get(typeKey);
		return call(HttpMethod.POST, "/api/v1/relations", token,
				Map.of("sourceNodeId", source, "targetNodeId", target, "relationTypeId", typeId));
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
				"email", "p-" + UUID.randomUUID() + "@example.com", "displayName", "Problem Tester",
				"password", "correct-horse-battery"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
