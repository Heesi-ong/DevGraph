package com.devgraph.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * 설계서 §19 Phase 4 테스트: duplicate/self/cross-workspace edge, cycle traversal, depth/limit, 허용 타입 조합,
 * 대칭 canonical 저장, 동시 생성, 응답 상한. 완료 조건: cycle graph에서도 응답 상한을 지킨다.
 */
class RelationGraphApiIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	JdbcTemplate jdbc;

	// ---- relation types ---------------------------------------------------------------------

	@Test
	void systemRelationTypesAreSeededWithTheDocumentedCombinations() {
		String token = signupToken();
		var body = call(HttpMethod.GET, "/api/v1/relation-types", token, null).getBody();

		assertThat(JsonPath.<List<String>>read(body, "$[*].key")).containsExactlyInAnyOrder("RELATED_TO", "IS_PART_OF",
				"DEPENDS_ON", "IS_EXAMPLE_OF", "IMPLEMENTS", "USED_IN", "OCCURRED_IN", "APPLIED_IN", "CAUSED_BY",
				"SOLVED_BY", "IMPLEMENTED_WITH", "LEARNED_FROM", "REFERENCES");
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.key=='DEPENDS_ON')].allowedSourceTypes[*]"))
				.containsExactlyInAnyOrder("CONCEPT", "SNIPPET");
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.key=='APPLIED_IN')].allowedSourceTypes[*]"))
				.containsExactly("SOLUTION"); // Snippet은 제외(§13.1)
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.key=='RELATED_TO')].directionality"))
				.containsExactly("symmetric");
		assertThat(JsonPath.<List<String>>read(body, "$[?(@.key=='IS_EXAMPLE_OF')].inverseLabel"))
				.containsExactly("has example");
		// 사용자 정의 타입 생성 경로는 존재하지 않는다(Growth 범위).
		assertThat(call(HttpMethod.POST, "/api/v1/relation-types", token, Map.of("key", "X")).getStatusCode()
				.is4xxClientError()).isTrue();
	}

	// ---- create / show on both sides --------------------------------------------------------

	@Test
	void relationShowsAsOutgoingOnSourceAndBacklinkOnTarget() {
		String token = signupToken();
		String concept = concept(token, "JWT");
		String snippet = snippet(token, "JWT filter");

		var created = createRelation(token, snippet, concept, "IS_EXAMPLE_OF", "인증 필터 예시");
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(created.getBody(), "$.type")).isEqualTo("IS_EXAMPLE_OF");

		var snippetDetail = call(HttpMethod.GET, "/api/v1/snippets/" + snippet, token, null).getBody();
		assertThat((String) JsonPath.read(snippetDetail, "$.relations.outgoing[0].label")).isEqualTo("is example of");
		assertThat((String) JsonPath.read(snippetDetail, "$.relations.outgoing[0].nodeTitle")).isEqualTo("JWT");
		assertThat((String) JsonPath.read(snippetDetail, "$.relations.outgoing[0].note")).isEqualTo("인증 필터 예시");

		// backlink는 중복 저장 없이 inverse label로 보인다(§13.1).
		var conceptDetail = call(HttpMethod.GET, "/api/v1/nodes/" + concept, token, null).getBody();
		assertThat(JsonPath.<List<?>>read(conceptDetail, "$.relations.outgoing")).isEmpty();
		assertThat((String) JsonPath.read(conceptDetail, "$.relations.incoming[0].label")).isEqualTo("has example");
		assertThat((String) JsonPath.read(conceptDetail, "$.relations.incoming[0].nodeTitle")).isEqualTo("JWT filter");
		assertThat((String) JsonPath.read(conceptDetail, "$.relations.incoming[0].nodeType")).isEqualTo("SNIPPET");
		assertThat(jdbc.queryForObject("select count(*) from knowledge_relations where workspace_id = "
				+ "(select workspace_id from knowledge_nodes where id = ?)", Integer.class, UUID.fromString(concept)))
				.isEqualTo(1);
	}

	@Test
	void validationRejectsSelfLoopUnknownTypeUnknownNodeAndDisallowedCombination() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String snippet = snippet(token, "S");

		var self = createRelation(token, a, a, "RELATED_TO", null);
		assertThat(self.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(self.getBody()).contains("SELF_RELATION_NOT_ALLOWED");

		// CONCEPT → CONCEPT는 IMPLEMENTS(Source는 SNIPPET만)를 허용하지 않는다.
		var notAllowed = createRelation(token, a, b, "IMPLEMENTS", null);
		assertThat(notAllowed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(notAllowed.getBody()).contains("TYPE_NOT_ALLOWED");
		// 방향이 반대면 허용된다.
		assertThat(createRelation(token, snippet, a, "IMPLEMENTS", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(createRelation(token, a, snippet, "IMPLEMENTS", null).getBody()).contains("TYPE_NOT_ALLOWED");

		var badType = call(HttpMethod.POST, "/api/v1/relations", token,
				Map.of("sourceNodeId", a, "targetNodeId", b, "relationTypeId", UUID.randomUUID().toString()));
		assertThat(badType.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(badType.getBody()).contains("INVALID_RELATION_TYPE");

		var badNode = call(HttpMethod.POST, "/api/v1/relations", token, Map.of("sourceNodeId", a,
				"targetNodeId", UUID.randomUUID().toString(), "relationTypeId", typeId(token, "RELATED_TO")));
		assertThat(badNode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(badNode.getBody()).contains("INVALID_RELATION_NODE");

		assertThat(createRelation(token, a, b, "RELATED_TO", "x".repeat(501)).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.POST, "/api/v1/relations", token, Map.of("sourceNodeId", a)).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void duplicateTripleIsRejectedButReverseDirectionOfADirectedTypeIsADifferentEdge() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");

		var first = createRelation(token, a, b, "DEPENDS_ON", null);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String firstId = JsonPath.read(first.getBody(), "$.id");

		var dup = createRelation(token, a, b, "DEPENDS_ON", "다시");
		assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(dup.getBody()).contains("DUPLICATE_RELATION").contains(firstId); // 기존 관계로 이동할 수 있게 id를 준다
		assertThat(createRelation(token, b, a, "DEPENDS_ON", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		// 같은 두 Node라도 타입이 다르면 별개다.
		assertThat(createRelation(token, a, b, "IS_PART_OF", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void symmetricRelationIsStoredOnceInCanonicalOrderRegardlessOfRequestDirection() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String low = a.compareTo(b) < 0 ? a : b;
		String high = low.equals(a) ? b : a;

		// 큰 id → 작은 id 순으로 요청해도 저장은 (작은, 큰)이다.
		var created = createRelation(token, high, low, "RELATED_TO", null);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(created.getBody(), "$.sourceNodeId")).isEqualTo(low);
		assertThat((String) JsonPath.read(created.getBody(), "$.targetNodeId")).isEqualTo(high);
		String existingId = JsonPath.read(created.getBody(), "$.id");

		// 반대 방향 재요청은 새 행을 만들지 않고 기존 관계를 가리키는 409(자동 반전 성공 처리 없음, §13.1.2)
		var reverse = createRelation(token, low, high, "RELATED_TO", null);
		assertThat(reverse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(reverse.getBody()).contains("DUPLICATE_RELATION").contains(existingId);

		// 양쪽 상세 모두 outgoing에 상대 Node가 나온다(방향 개념 없음).
		for (String[] pair : new String[][] { { a, b }, { b, a } }) {
			var detail = call(HttpMethod.GET, "/api/v1/nodes/" + pair[0], token, null).getBody();
			assertThat(JsonPath.<List<?>>read(detail, "$.relations.incoming")).isEmpty();
			assertThat((String) JsonPath.read(detail, "$.relations.outgoing[0].nodeId")).isEqualTo(pair[1]);
			assertThat((String) JsonPath.read(detail, "$.relations.outgoing[0].label")).isEqualTo("related to");
		}
	}

	@Test
	void concurrentIdenticalRequestsCreateExactlyOneEdge() throws Exception {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");

		typeId(token, "RELATED_TO"); // 스레드가 캐시를 동시에 채우지 않도록 미리 조회해 둔다.
		int contenders = 6;
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < contenders; i++) {
			boolean flip = i % 2 == 1; // 대칭 타입은 방향을 섞어도 같은 canonical edge다.
			results.add(pool.submit(() -> {
				start.await();
				return createRelation(token, flip ? b : a, flip ? a : b, "RELATED_TO", null).getStatusCode().value();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : results) {
			statuses.add(f.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 409)).hasSize(contenders - 1);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_relations where source_node_id in (?, ?)",
				Integer.class, UUID.fromString(a), UUID.fromString(b))).isEqualTo(1);
	}

	// ---- update / delete --------------------------------------------------------------------
	@Test
	void symmetricToDirectedUsesExplicitScreenDirectionAndRejectsDifferentEndpoints() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String c = concept(token, "C");
		String high = a.compareTo(b) > 0 ? a : b;
		String low = a.compareTo(b) > 0 ? b : a;
		String id = JsonPath.read(createRelation(token, high, low, "RELATED_TO", null).getBody(), "$.id");
		var missing = call(HttpMethod.PATCH, "/api/v1/relations/" + id, token,
				Map.of("relationTypeId", typeId(token, "IS_PART_OF")));
		assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(missing.getBody()).contains("RELATION_DIRECTION_REQUIRED");
		var changed = call(HttpMethod.PATCH, "/api/v1/relations/" + id, token,
				Map.of("relationTypeId", typeId(token, "IS_PART_OF"), "sourceNodeId", high, "targetNodeId", low));
		assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(JsonPath.<String>read(changed.getBody(), "$.sourceNodeId")).isEqualTo(high);
		assertThat(JsonPath.<String>read(changed.getBody(), "$.targetNodeId")).isEqualTo(low);
		var invalid = call(HttpMethod.PATCH, "/api/v1/relations/" + id, token,
				Map.of("sourceNodeId", high, "targetNodeId", c));
		assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		var back = call(HttpMethod.PATCH, "/api/v1/relations/" + id, token,
				Map.of("relationTypeId", typeId(token, "RELATED_TO"), "sourceNodeId", high, "targetNodeId", low));
		assertThat(back.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(JsonPath.<String>read(back.getBody(), "$.sourceNodeId")).isEqualTo(low);
	}

	@Test
	void graphDeduplicatesNeighborsBeforeApplyingTheQueryLimit() {
		String token = signupToken();
		String hub = concept(token, "Hub");
		UUID hubId = UUID.fromString(hub);
		UUID workspace = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, hubId);
		UUID user = jdbc.queryForObject("select created_by from knowledge_nodes where id = ?", UUID.class, hubId);
		// 500 recent neighbors × 12 types exceed the former 5,000 edge-row cap. The older neighbor must still
		// be returned as an expansion candidate after the 499 available slots are filled.
		jdbc.update("""
				insert into knowledge_nodes(workspace_id, created_by, node_type, title, updated_at)
				select ?, ?, 'CONCEPT', 'dense ' || g, now() from generate_series(1, 500) g""", workspace, user);
		String older = concept(token, "Older neighbor");
		jdbc.update("update knowledge_nodes set updated_at = now() - interval '1 day' where id = ?", UUID.fromString(older));
		jdbc.update("""
				insert into knowledge_relations(workspace_id, source_node_id, target_node_id, relation_type_id, created_by)
				select ?, ?, n.id, t.id, ? from knowledge_nodes n cross join relation_types t
				where n.workspace_id = ? and n.id <> ? and t.key <> 'RELATED_TO'""", workspace, hubId, user, workspace, hubId);
		var response = graph(token, hub, "depth=1&maxNodes=500");
		assertThat(JsonPath.<List<?>>read(response, "$.nodes")).hasSize(500);
		assertThat(JsonPath.<List<String>>read(response, "$.nextExpansionCandidates[*].nodeId")).contains(older);
		assertThat(JsonPath.<String>read(response, "$.truncationReason")).isEqualTo("MAX_NODES");
	}

	@Test
	void updateChangesTypeAndNoteWithSameRulesAndDeleteRemovesTheEdge() throws InterruptedException {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String snippet = snippet(token, "S");
		String relationId = JsonPath.read(createRelation(token, a, b, "IS_PART_OF", "초안").getBody(), "$.id");

		// null은 변경 없음, 빈 문자열은 지움
		var same = call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, token, Map.of());
		assertThat((String) JsonPath.read(same.getBody(), "$.note")).isEqualTo("초안");
		var typed = call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, token,
				Map.of("relationTypeId", typeId(token, "DEPENDS_ON"), "note", ""));
		assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(typed.getBody(), "$.type")).isEqualTo("DEPENDS_ON");
		assertThat((Object) JsonPath.read(typed.getBody(), "$.note")).isNull();

		// 허용되지 않는 조합으로 바꾸는 것은 거부(CONCEPT → CONCEPT는 IMPLEMENTS 불가)
		var bad = call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, token,
				Map.of("relationTypeId", typeId(token, "IMPLEMENTS")));
		assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(bad.getBody()).contains("TYPE_NOT_ALLOWED");

		// 이미 있는 (source,type,target)으로 바꾸면 409
		createRelation(token, a, b, "IS_PART_OF", null);
		var dup = call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, token,
				Map.of("relationTypeId", typeId(token, "IS_PART_OF")));
		assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(dup.getBody()).contains("DUPLICATE_RELATION");
		assertThat(JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/nodes/" + a, token, null).getBody(),
				"$.relations.outgoing[?(@.id=='" + relationId + "')].type")).containsExactly("DEPENDS_ON");

		// 방향성 → 대칭으로 바꾸면 canonical 순서로 저장 방향이 정리된다.
		String low = a.compareTo(snippet) < 0 ? a : snippet;
		String high = low.equals(a) ? snippet : a;
		String dirId = JsonPath.read(createRelation(token, high, low, "IS_PART_OF", null).getBody(), "$.id");
		var sym = call(HttpMethod.PATCH, "/api/v1/relations/" + dirId, token,
				Map.of("relationTypeId", typeId(token, "RELATED_TO"), "sourceNodeId", high, "targetNodeId", low));
		assertThat(sym.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(sym.getBody(), "$.sourceNodeId")).isEqualTo(low);

		assertThat(call(HttpMethod.DELETE, "/api/v1/relations/" + relationId, token, null).getStatusCode())
				.isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(call(HttpMethod.DELETE, "/api/v1/relations/" + relationId, token, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, token, Map.of("note", "x"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		awaitActions(UUID.fromString(relationId), 3);
		assertThat(jdbc.queryForList("select action from activity_logs where object_id = ? order by created_at, action",
				String.class, UUID.fromString(relationId)))
				.containsExactlyInAnyOrder("RELATION_CREATED", "RELATION_UPDATED", "RELATION_DELETED");
	}

	// ---- isolation and node state ------------------------------------------------------------

	@Test
	void otherWorkspaceCannotCreateReadChangeOrTraverseRelations() {
		String owner = signupToken();
		String intruder = signupToken();
		String a = concept(owner, "A");
		String b = concept(owner, "B");
		String mine = concept(intruder, "Mine");
		String relationId = JsonPath.read(createRelation(owner, a, b, "RELATED_TO", null).getBody(), "$.id");

		// 남의 Node를 끌어오는 관계 생성은 거부(존재 여부를 드러내지 않는다)
		for (var r : List.of(createRelation(intruder, mine, a, "RELATED_TO", null),
				createRelation(intruder, a, b, "RELATED_TO", null), createRelation(intruder, a, mine, "RELATED_TO", null))) {
			assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(r.getBody()).contains("INVALID_RELATION_NODE");
		}
		assertThat(call(HttpMethod.PATCH, "/api/v1/relations/" + relationId, intruder, Map.of("note", "hack"))
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.DELETE, "/api/v1/relations/" + relationId, intruder, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + a, intruder, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/relations/target-candidates?nodeId=" + a + "&relationTypeId="
				+ typeId(intruder, "RELATED_TO"), intruder, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// 내 후보에 남의 Node가 섞이지 않는다.
		var candidates = call(HttpMethod.GET, "/api/v1/relations/target-candidates?nodeId=" + mine
				+ "&relationTypeId=" + typeId(intruder, "RELATED_TO"), intruder, null).getBody();
		assertThat(JsonPath.<List<?>>read(candidates, "$")).isEmpty();
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/graph/workspace", intruder, null).getBody(),
				"$.edges")).isEmpty();
		// 원본은 그대로
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/nodes/" + a, owner, null).getBody(),
				"$.relations.outgoing")).hasSize(1);
	}

	@Test
	void trashedNodesCannotBeLinkedAndDisappearFromDetailAndGraph() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String c = concept(token, "C");
		createRelation(token, a, b, "RELATED_TO", null);
		createRelation(token, a, c, "RELATED_TO", null);

		assertThat(call(HttpMethod.POST, "/api/v1/nodes/" + b + "/trash", token, Map.of("version", 0)).getStatusCode())
				.isEqualTo(HttpStatus.OK);
		var toTrashed = createRelation(token, c, b, "IS_PART_OF", null);
		assertThat(toTrashed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(toTrashed.getBody()).contains("INVALID_NODE_STATE");

		var detail = call(HttpMethod.GET, "/api/v1/nodes/" + a, token, null).getBody();
		assertThat(JsonPath.<List<String>>read(detail, "$.relations.outgoing[*].nodeId")).containsExactly(c);
		var graph = call(HttpMethod.GET, "/api/v1/graph/focus/" + a + "?depth=2", token, null).getBody();
		assertThat(JsonPath.<List<String>>read(graph, "$.nodes[*].id")).containsExactlyInAnyOrder(a, c);
		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + b, token, null).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ---- relation picker candidates ----------------------------------------------------------

	@Test
	void targetCandidatesRespectAllowedTypesExistingLinksTitleQueryAndWildcards() {
		String token = signupToken();
		String source = snippet(token, "Source snippet");
		String jwt = concept(token, "JWT");
		String jpa = concept(token, "JPA");
		String percent = concept(token, "100%_done");
		String note = call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "NOTE", "title", "JWT memo"))
				.getBody();
		String noteId = JsonPath.read(note, "$.id");
		String trashed = concept(token, "JWT trashed");
		call(HttpMethod.POST, "/api/v1/nodes/" + trashed + "/trash", token, Map.of("version", 0));
		String impl = typeId(token, "IMPLEMENTS"); // SNIPPET → CONCEPT

		// 허용 Target(CONCEPT)만, 휴지통·Note·자기 자신 제외
		var all = candidates(token, source, impl, "OUTGOING", null);
		assertThat(JsonPath.<List<String>>read(all, "$[*].id")).containsExactlyInAnyOrder(jwt, jpa, percent);
		assertThat(JsonPath.<List<String>>read(all, "$[*].id")).doesNotContain(noteId, trashed, source);

		// 부분 일치(대소문자 무시)
		assertThat(JsonPath.<List<String>>read(candidates(token, source, impl, "OUTGOING", "jwt"), "$[*].id"))
				.containsExactly(jwt);
		// LIKE 와일드카드는 문자 그대로 검색된다.
		assertThat(JsonPath.<List<String>>read(candidates(token, source, impl, "OUTGOING", "%"), "$[*].id"))
				.containsExactly(percent);
		assertThat(JsonPath.<List<String>>read(candidates(token, source, impl, "OUTGOING", "_"), "$[*].id"))
				.containsExactly(percent);

		// 이미 연결된 것은 제외
		createRelation(token, source, jwt, "IMPLEMENTS", null);
		assertThat(JsonPath.<List<String>>read(candidates(token, source, impl, "OUTGOING", null), "$[*].id"))
				.containsExactlyInAnyOrder(jpa, percent);

		// 반대 방향(이 Node가 target): CONCEPT는 IMPLEMENTS의 target이므로 Source 후보는 SNIPPET
		var incoming = candidates(token, jwt, impl, "INCOMING", null);
		assertThat(JsonPath.<List<String>>read(incoming, "$[*].id")).isEmpty(); // 이미 source가 연결됨
		var incomingJpa = candidates(token, jpa, impl, "INCOMING", null);
		assertThat(JsonPath.<List<String>>read(incomingJpa, "$[*].id")).containsExactly(source);
		// 이 Node의 타입이 그 방향으로 허용되지 않으면 빈 목록(CONCEPT는 IMPLEMENTS의 source가 될 수 없다)
		assertThat(JsonPath.<List<?>>read(candidates(token, jwt, impl, "OUTGOING", null), "$")).isEmpty();
	}

	// ---- graph: focus traversal --------------------------------------------------------------

	@Test
	void focusGraphExpandsBreadthFirstByDepthAndKeepsLowestDepth() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String c = concept(token, "C");
		String d = concept(token, "D");
		createRelation(token, a, b, "IS_PART_OF", null);
		createRelation(token, b, c, "IS_PART_OF", null);
		createRelation(token, c, d, "IS_PART_OF", null);
		createRelation(token, a, c, "RELATED_TO", null); // C는 depth 1로도 depth 2로도 닿는다 → 1

		var d1 = graph(token, a, "depth=1");
		assertThat(JsonPath.<List<String>>read(d1, "$.nodes[*].id")).containsExactlyInAnyOrder(a, b, c);
		assertThat(JsonPath.<List<Integer>>read(d1, "$.nodes[?(@.id=='" + c + "')].depth")).containsExactly(1);
		assertThat(JsonPath.<List<Integer>>read(d1, "$.nodes[?(@.id=='" + a + "')].depth")).containsExactly(0);
		// 포함된 Node 사이의 edge는 depth 경계와 무관하게 모두 나온다(B→C 포함).
		assertThat(JsonPath.<List<?>>read(d1, "$.edges")).hasSize(3);
		assertThat((Boolean) JsonPath.read(d1, "$.truncated")).isFalse();
		// depth 1의 다음 단계 후보: D
		assertThat(JsonPath.<List<String>>read(d1, "$.nextExpansionCandidates[*].nodeId")).containsExactly(d);
		assertThat((Integer) JsonPath.read(d1, "$.appliedFilters.depth")).isEqualTo(1);

		var d2 = graph(token, a, "depth=2");
		assertThat(JsonPath.<List<String>>read(d2, "$.nodes[*].id")).containsExactlyInAnyOrder(a, b, c, d);
		assertThat(JsonPath.<List<Integer>>read(d2, "$.nodes[?(@.id=='" + d + "')].depth")).containsExactly(2);
		assertThat(JsonPath.<List<?>>read(d2, "$.nextExpansionCandidates")).isEmpty();

		var byDefault = graph(token, a, "");
		assertThat((Integer) JsonPath.read(byDefault, "$.appliedFilters.depth")).isEqualTo(1);

		var tooDeep = call(HttpMethod.GET, "/api/v1/graph/focus/" + a + "?depth=4", token, null);
		assertThat(tooDeep.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(tooDeep.getBody()).contains("LIMIT_EXCEEDED");
		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + a + "?depth=0", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + a + "?maxNodes=501", token, null).getBody())
				.contains("LIMIT_EXCEEDED");
	}

	@Test
	void cycleTerminatesAndEveryNodeAppearsOnce() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		String c = concept(token, "C");
		createRelation(token, a, b, "DEPENDS_ON", null);
		createRelation(token, b, c, "DEPENDS_ON", null);
		createRelation(token, c, a, "DEPENDS_ON", null);

		var response = graph(token, a, "depth=3");
		assertThat(JsonPath.<List<String>>read(response, "$.nodes[*].id")).containsExactlyInAnyOrder(a, b, c);
		assertThat(JsonPath.<List<?>>read(response, "$.edges")).hasSize(3);
		assertThat((Boolean) JsonPath.read(response, "$.truncated")).isFalse();
		assertThat(JsonPath.<List<?>>read(response, "$.nextExpansionCandidates")).isEmpty();
	}

	@Test
	void nodeLimitCutsDeterministicallyAndReportsTruncationWithCandidates() throws InterruptedException {
		String token = signupToken();
		String hub = concept(token, "Hub");
		List<String> spokes = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String spoke = concept(token, "Spoke " + i);
			spokes.add(spoke);
			createRelation(token, hub, spoke, "IS_PART_OF", null);
			Thread.sleep(5); // updatedAt 순서를 분명히 한다.
		}

		var response = graph(token, hub, "maxNodes=4");
		assertThat(JsonPath.<List<?>>read(response, "$.nodes")).hasSize(4);
		assertThat((Boolean) JsonPath.read(response, "$.truncated")).isTrue();
		assertThat((String) JsonPath.read(response, "$.truncationReason")).isEqualTo("MAX_NODES");
		assertThat((Integer) JsonPath.read(response, "$.limits.maxNodes")).isEqualTo(4);
		// 같은 depth에서는 최근 수정 Node부터 담는다: Spoke 5, 4, 3
		assertThat(JsonPath.<List<String>>read(response, "$.nodes[*].title")).containsExactly("Hub", "Spoke 5",
				"Spoke 4", "Spoke 3");
		// 상한 때문에 빠진 것은 후보로 안내한다(포함된 Node와 직접 연결된 것).
		assertThat(JsonPath.<List<String>>read(response, "$.nextExpansionCandidates[*].title"))
				.containsExactly("Spoke 2", "Spoke 1", "Spoke 0");
		assertThat((String) JsonPath.read(response, "$.nextExpansionCandidates[0].viaRelation")).isEqualTo("IS_PART_OF");
		// 잘려도 edge는 포함된 Node 사이만이다.
		assertThat(JsonPath.<List<?>>read(response, "$.edges")).hasSize(3);
		// 같은 요청은 같은 결과(결정적)
		assertThat((Object) JsonPath.read(graph(token, hub, "maxNodes=4"), "$.nodes[*].id"))
				.isEqualTo(JsonPath.read(response, "$.nodes[*].id"));
	}

	@Test
	void edgeLimitIsEnforcedEvenWhenManyEdgesConnectFewNodes() {
		String token = signupToken();
		List<String> nodes = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			nodes.add(concept(token, "N" + i));
		}
		UUID first = UUID.fromString(nodes.get(0));
		UUID workspaceId = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, first);
		UUID userId = jdbc.queryForObject("select created_by from knowledge_nodes where id = ?", UUID.class, first);
		UUID dependsOn = UUID.fromString(typeId(token, "DEPENDS_ON"));
		// 60개 Node의 모든 순서쌍(3,540 edge)을 DB에 직접 넣는다. API로는 만들기 오래 걸리는 상황을 재현한다.
		jdbc.update("""
				insert into knowledge_relations (workspace_id, source_node_id, target_node_id, relation_type_id, created_by)
				select ?, s.id, t.id, ?, ? from knowledge_nodes s, knowledge_nodes t
				where s.workspace_id = ? and t.workspace_id = ? and s.id <> t.id""", workspaceId, dependsOn, userId,
				workspaceId, workspaceId);

		var focus = graph(token, nodes.get(0), "maxNodes=500");
		assertThat(JsonPath.<List<?>>read(focus, "$.nodes")).hasSize(60);
		assertThat(JsonPath.<List<?>>read(focus, "$.edges")).hasSize(1500);
		assertThat((Boolean) JsonPath.read(focus, "$.truncated")).isTrue();
		assertThat((String) JsonPath.read(focus, "$.truncationReason")).isEqualTo("MAX_EDGES");

		var workspace = call(HttpMethod.GET, "/api/v1/graph/workspace?size=100", token, null).getBody();
		assertThat(JsonPath.<List<?>>read(workspace, "$.edges")).hasSize(1500);
		assertThat((String) JsonPath.read(workspace, "$.truncationReason")).isEqualTo("MAX_EDGES");
	}

	@Test
	void hugeNumberOfRelationsOnOneNodeIsCappedInDetailAndTruncationIsReported() {
		String token = signupToken();
		String hub = concept(token, "Hub");
		UUID hubId = UUID.fromString(hub);
		UUID workspaceId = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, hubId);
		UUID userId = jdbc.queryForObject("select created_by from knowledge_nodes where id = ?", UUID.class, hubId);
		jdbc.update("""
				insert into knowledge_nodes (id, workspace_id, created_by, node_type, title)
				select gen_random_uuid(), ?, ?, 'CONCEPT', 'bulk ' || g from generate_series(1, 205) g""", workspaceId,
				userId);
		jdbc.update("""
				insert into knowledge_relations (workspace_id, source_node_id, target_node_id, relation_type_id, created_by)
				select ?, ?, n.id, (select id from relation_types where key = 'IS_PART_OF'), ?
				from knowledge_nodes n where n.workspace_id = ? and n.title like 'bulk %'""", workspaceId, hubId, userId,
				workspaceId);

		var detail = call(HttpMethod.GET, "/api/v1/nodes/" + hub, token, null).getBody();
		assertThat(JsonPath.<List<?>>read(detail, "$.relations.outgoing")).hasSize(200);
		assertThat((Boolean) JsonPath.read(detail, "$.relations.truncated")).isTrue();
	}

	// ---- graph: filters and workspace listing -------------------------------------------------

	@Test
	void focusFiltersByNodeTypeRelationTypeAndArchivedState() {
		String token = signupToken();
		String center = concept(token, "Center");
		String other = concept(token, "Other concept");
		String snippet = snippet(token, "Snippet");
		String archived = concept(token, "Archived");
		createRelation(token, center, other, "IS_PART_OF", null);
		createRelation(token, snippet, center, "IMPLEMENTS", null);
		createRelation(token, center, archived, "RELATED_TO", null);
		call(HttpMethod.POST, "/api/v1/nodes/" + archived + "/archive", token, Map.of("version", 0));

		var byDefault = graph(token, center, "depth=1");
		assertThat(JsonPath.<List<String>>read(byDefault, "$.nodes[*].id")).containsExactlyInAnyOrder(center, other, snippet);

		var withArchived = graph(token, center, "archived=true");
		assertThat(JsonPath.<List<String>>read(withArchived, "$.nodes[*].id"))
				.containsExactlyInAnyOrder(center, other, snippet, archived);
		assertThat((Boolean) JsonPath.read(withArchived, "$.appliedFilters.includeArchived")).isTrue();

		var onlySnippets = graph(token, center, "nodeTypes=SNIPPET");
		assertThat(JsonPath.<List<String>>read(onlySnippets, "$.nodes[*].id")).containsExactlyInAnyOrder(center, snippet);
		assertThat(JsonPath.<List<String>>read(onlySnippets, "$.appliedFilters.nodeTypes")).containsExactly("SNIPPET");

		var onlyPartOf = graph(token, center, "relationTypes=IS_PART_OF");
		assertThat(JsonPath.<List<String>>read(onlyPartOf, "$.nodes[*].id")).containsExactlyInAnyOrder(center, other);
		assertThat(JsonPath.<List<String>>read(onlyPartOf, "$.edges[*].type")).containsExactly("IS_PART_OF");

		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + center + "?relationTypes=NOPE", token, null)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(call(HttpMethod.GET, "/api/v1/graph/focus/" + center + "?nodeTypes=NOPE", token, null)
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void workspaceGraphPagesNodesAndOnlyReturnsEdgesInsideThePage() throws InterruptedException {
		String token = signupToken();
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(concept(token, "W" + i));
			Thread.sleep(5);
		}
		// 최신 순서: W4, W3, W2, W1, W0
		createRelation(token, created.get(4), created.get(3), "IS_PART_OF", null); // 같은 페이지 안
		createRelation(token, created.get(3), created.get(0), "IS_PART_OF", null); // 페이지를 가로지름

		var first = call(HttpMethod.GET, "/api/v1/graph/workspace?size=2", token, null).getBody();
		assertThat(JsonPath.<List<String>>read(first, "$.nodes[*].title")).containsExactly("W4", "W3");
		assertThat(JsonPath.<List<?>>read(first, "$.edges")).hasSize(1);
		assertThat(JsonPath.<List<?>>read(first, "$.nextExpansionCandidates")).isEmpty();
		String cursor = JsonPath.read(first, "$.cursor");
		assertThat(cursor).isNotBlank();

		var second = call(HttpMethod.GET, "/api/v1/graph/workspace?size=2&cursor=" + cursor, token, null).getBody();
		assertThat(JsonPath.<List<String>>read(second, "$.nodes[*].title")).containsExactly("W2", "W1");
		assertThat(JsonPath.<List<?>>read(second, "$.edges")).isEmpty();

		assertThat(call(HttpMethod.GET, "/api/v1/graph/workspace?cursor=bad", token, null).getBody())
				.contains("INVALID_CURSOR");
		assertThat(call(HttpMethod.GET, "/api/v1/graph/workspace?size=101", token, null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void workspaceGraphFiltersByTypeTagAndArchivedAndIncludesSnippets() {
		String token = signupToken();
		String tagId = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", token, Map.of("name", "auth")).getBody(),
				"$.id");
		String tagged = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", token,
				Map.of("type", "CONCEPT", "title", "Tagged", "tagIds", List.of(tagId))).getBody(), "$.id");
		String plain = concept(token, "Plain");
		String snippet = snippet(token, "Snip");
		String archived = concept(token, "Old");
		call(HttpMethod.POST, "/api/v1/nodes/" + archived + "/archive", token, Map.of("version", 0));

		assertThat(titles(token, "")).containsExactlyInAnyOrder("Tagged", "Plain", "Snip");
		assertThat(titles(token, "archived=true")).containsExactlyInAnyOrder("Tagged", "Plain", "Snip", "Old");
		assertThat(titles(token, "nodeTypes=SNIPPET")).containsExactly("Snip");
		assertThat(titles(token, "tag=" + tagId)).containsExactly("Tagged");
		assertThat(tagged).isNotEqualTo(plain);
	}

	// ---- database-level defenses --------------------------------------------------------------

	@Test
	void databaseConstraintsAreTheLastLineOfDefense() {
		String token = signupToken();
		String a = concept(token, "A");
		String b = concept(token, "B");
		UUID aId = UUID.fromString(a);
		UUID bId = UUID.fromString(b);
		UUID workspaceId = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, aId);
		UUID userId = jdbc.queryForObject("select created_by from knowledge_nodes where id = ?", UUID.class, aId);
		UUID type = UUID.fromString(typeId(token, "DEPENDS_ON"));
		String insert = "insert into knowledge_relations (workspace_id, source_node_id, target_node_id, relation_type_id, created_by)"
				+ " values (?, ?, ?, ?, ?)";

		assertThatThrownBy(() -> jdbc.update(insert, workspaceId, aId, aId, type, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_knowledge_relations__no_self_loop");
		jdbc.update(insert, workspaceId, aId, bId, type, userId);
		assertThatThrownBy(() -> jdbc.update(insert, workspaceId, aId, bId, type, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_knowledge_relations__edge");
		// 다른 Workspace로 위장한 edge는 복합 FK가 막는다.
		String otherToken = signupToken();
		UUID otherWorkspace = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class,
				UUID.fromString(concept(otherToken, "Other")));
		assertThatThrownBy(() -> jdbc.update(insert, otherWorkspace, aId, bId, type, userId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("fk_knowledge_relations__");
		// 허용 타입 배열에 없는 값은 CHECK가 막는다.
		assertThatThrownBy(() -> jdbc.update("""
				insert into relation_types (workspace_id, key, forward_label, inverse_label, directionality,
				  allowed_source_types, allowed_target_types, is_system)
				values (?, 'X', 'x', 'x', 'directed', ARRAY['NOPE'], ARRAY['CONCEPT'], false)""", workspaceId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_relation_types__allowed_source_types");
		// §12.3 정합성: Node를 지우면 edge도 함께 사라진다(cascade).
		jdbc.update("delete from knowledge_nodes where id = ?", bId);
		assertThat(jdbc.queryForObject("select count(*) from knowledge_relations where source_node_id = ?",
				Integer.class, aId)).isZero();
	}

	// ---- helpers ------------------------------------------------------------------------------

	private void awaitActions(UUID objectId, int expected) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			Integer count = jdbc.queryForObject("select count(*) from activity_logs where object_id = ?", Integer.class,
					objectId);
			if (count != null && count >= expected) {
				return;
			}
			Thread.sleep(100);
		}
	}

	private List<String> titles(String token, String query) {
		return JsonPath.read(call(HttpMethod.GET, "/api/v1/graph/workspace?" + query, token, null).getBody(),
				"$.nodes[*].title");
	}

	private String graph(String token, String nodeId, String query) {
		var response = call(HttpMethod.GET, "/api/v1/graph/focus/" + nodeId + "?" + query, token, null);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private String candidates(String token, String nodeId, String typeId, String side, String q) {
		String path = "/api/v1/relations/target-candidates?nodeId=" + nodeId + "&relationTypeId=" + typeId + "&side="
				+ side + (q == null ? "" : "&q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8));
		var response = call(HttpMethod.GET, path, token, null);
		assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private final Map<String, Map<String, String>> typeIdCache = new HashMap<>();

	private String typeId(String token, String key) {
		return typeIdCache.computeIfAbsent(token, t -> {
			var body = call(HttpMethod.GET, "/api/v1/relation-types", t, null).getBody();
			Map<String, String> ids = new HashMap<>();
			List<String> keys = JsonPath.read(body, "$[*].key");
			List<String> values = JsonPath.read(body, "$[*].id");
			for (int i = 0; i < keys.size(); i++) {
				ids.put(keys.get(i), values.get(i));
			}
			return ids;
		}).get(key);
	}

	private ResponseEntity<String> createRelation(String token, String source, String target, String typeKey,
			String note) {
		Map<String, Object> body = new HashMap<>();
		body.put("sourceNodeId", source);
		body.put("targetNodeId", target);
		body.put("relationTypeId", typeId(token, typeKey));
		if (note != null) {
			body.put("note", note);
		}
		return call(HttpMethod.POST, "/api/v1/relations", token, body);
	}

	private String concept(String token, String title) {
		var response = call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "CONCEPT", "title", title));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private String snippet(String token, String title) {
		var response = call(HttpMethod.POST, "/api/v1/snippets", token,
				Map.of("title", title, "language", "java", "code", "class A {}"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.id");
	}

	private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(java.net.URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers), String.class);
	}

	private String signupToken() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(Map.of(
				"email", "r-" + UUID.randomUUID() + "@example.com", "displayName", "Relation Tester",
				"password", "correct-horse-battery"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
