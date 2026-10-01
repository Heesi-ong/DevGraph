package com.devgraph.graph.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.CursorCodec;
import com.devgraph.common.web.PageSize;
import com.devgraph.graph.application.GraphViews.AppliedFilters;
import com.devgraph.graph.application.GraphViews.ExpansionCandidate;
import com.devgraph.graph.application.GraphViews.GraphEdge;
import com.devgraph.graph.application.GraphViews.GraphNode;
import com.devgraph.graph.application.GraphViews.GraphResponse;
import com.devgraph.graph.application.GraphViews.Limits;
import com.devgraph.graph.infrastructure.GraphQueryRepository;
import com.devgraph.graph.infrastructure.GraphQueryRepository.EdgeRow;
import com.devgraph.graph.infrastructure.GraphQueryRepository.NeighborRow;
import com.devgraph.graph.infrastructure.GraphQueryRepository.NodeRow;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.relation.application.RelationQueryService;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §13.3 Graph 조회 정책. 중심 Graph는 낮은 depth부터 BFS로 확장하고, 상한에서 결정적으로 자르며,
 * 잘림을 응답에 숨기지 않는다. visited 집합으로 cycle에서도 종료하고, 여러 경로로 닿는 Node는 가장 낮은 depth로 한 번만 담는다.
 * ponytail: 설계서는 recursive CTE를 예시하지만 depth 상한이 3이라 depth당 쿼리 1번(최대 4번)인 애플리케이션 BFS로
 * 같은 의미를 구현했다. depth 상한을 크게 늘리거나 경로 질의가 필요해지면 CTE로 바꾼다.
 */
@Service
public class GraphService {

	static final int DEFAULT_DEPTH = 1;
	static final int MAX_DEPTH = 3;
	static final int DEFAULT_MAX_NODES = 200;
	static final int HARD_MAX_NODES = 500;
	static final int HARD_MAX_EDGES = 1500;
	static final int MAX_CANDIDATES = 20;
	static final int DEFAULT_WORKSPACE_SIZE = 50;
	// 허브 Node 하나가 수만 edge를 가져도 한 번에 읽는 행 수를 묶는다.
	private static final int NEIGHBOR_ROW_CAP = 5000;

	private final GraphQueryRepository repository;
	private final RelationQueryService relationQueryService;
	private final WorkspaceQueryService workspaceQueryService;

	public GraphService(GraphQueryRepository repository, RelationQueryService relationQueryService,
			WorkspaceQueryService workspaceQueryService) {
		this.repository = repository;
		this.relationQueryService = relationQueryService;
		this.workspaceQueryService = workspaceQueryService;
	}

	@Transactional(readOnly = true)
	public GraphResponse focus(UUID userId, UUID nodeId, Integer requestedDepth, List<NodeType> nodeTypes,
			List<String> relationTypes, Integer requestedMaxNodes, boolean includeArchived) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int depth = requestedDepth == null ? DEFAULT_DEPTH : requestedDepth;
		int maxNodes = requestedMaxNodes == null ? DEFAULT_MAX_NODES : requestedMaxNodes;
		requireRange("depth", depth, 1, MAX_DEPTH);
		requireRange("maxNodes", maxNodes, 1, HARD_MAX_NODES);
		List<NodeType> types = nodeTypes == null ? List.of() : nodeTypes.stream().distinct().toList();
		List<String> keys = validRelationKeys(userId, relationTypes);
		int maxEdges = Math.min(HARD_MAX_EDGES, maxNodes * 3);
		Set<NodeStatus> statuses = includeArchived ? EnumSet.of(NodeStatus.ACTIVE, NodeStatus.ARCHIVED)
				: EnumSet.of(NodeStatus.ACTIVE);

		NodeRow focus = repository.findNode(workspaceId, nodeId);
		if (focus == null || focus.status() == NodeStatus.TRASHED) {
			throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
		}

		Map<UUID, GraphNode> included = new LinkedHashMap<>();
		included.put(focus.id(), node(focus, 0));
		List<ExpansionCandidate> candidates = new ArrayList<>();
		boolean truncated = false;
		String reason = "NONE";
		List<UUID> frontier = List.of(focus.id());

		for (int level = 1; level <= depth && !frontier.isEmpty(); level++) {
			List<NeighborRow> rows = repository.neighbors(workspaceId, frontier, statuses, types, keys,
					included.keySet(), NEIGHBOR_ROW_CAP + 1);
			boolean queryCapped = rows.size() > NEIGHBOR_ROW_CAP;
			if (queryCapped) {
				rows = rows.subList(0, NEIGHBOR_ROW_CAP);
			}
			List<NeighborRow> fresh = firstPerNode(rows, included);
			int capacity = maxNodes - included.size();
			List<NeighborRow> taken = fresh.subList(0, Math.min(capacity, fresh.size()));
			List<NeighborRow> cut = fresh.subList(taken.size(), fresh.size());
			for (NeighborRow row : taken) {
				included.put(row.node().id(), node(row.node(), level));
			}
			if (!cut.isEmpty() || queryCapped) {
				truncated = true;
				reason = !cut.isEmpty() ? "MAX_NODES" : "QUERY_ROW_CAP";
				addCandidates(candidates, cut);
				break; // 상한에 닿았다. 더 깊은 단계는 후보로 안내한다.
			}
			frontier = taken.stream().map(r -> r.node().id()).toList();
		}
		// 요청 depth까지 다 탐색했다면 그 다음 한 걸음의 Node를 확장 후보로 보여 준다(§13.3).
		if (!truncated && !frontier.isEmpty() && candidates.isEmpty()) {
			List<NeighborRow> beyond = repository.neighbors(workspaceId, frontier, statuses, types, keys,
					included.keySet(), NEIGHBOR_ROW_CAP);
			addCandidates(candidates, firstPerNode(beyond, included));
		}

		List<EdgeRow> edges = repository.edgesAmong(workspaceId, included.keySet(), keys, maxEdges + 1);
		if (edges.size() > maxEdges) {
			edges = edges.subList(0, maxEdges);
			if (!truncated) {
				truncated = true;
				reason = "MAX_EDGES";
			}
		}
		return new GraphResponse(List.copyOf(included.values()), edges.stream().map(GraphService::edge).toList(),
				truncated, reason, applied(depth, types, keys, includeArchived),
				candidates.stream().limit(MAX_CANDIDATES).toList(), null, new Limits(maxNodes, maxEdges));
	}

	/**
	 * Workspace Graph: traversal이 아니라 필터링된 Node 목록 pagination이다(§13.3). edge는 이번 페이지 Node
	 * 집합 안쪽만 반환하므로 페이지를 넘나드는 edge는 포함되지 않는다.
	 */
	@Transactional(readOnly = true)
	public GraphResponse workspace(UUID userId, List<NodeType> nodeTypes, List<String> relationTypes, UUID tagId,
			boolean includeArchived, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize, DEFAULT_WORKSPACE_SIZE);
		List<NodeType> types = nodeTypes == null ? List.of() : nodeTypes.stream().distinct().toList();
		List<String> keys = validRelationKeys(userId, relationTypes);
		Set<NodeStatus> statuses = includeArchived ? EnumSet.of(NodeStatus.ACTIVE, NodeStatus.ARCHIVED)
				: EnumSet.of(NodeStatus.ACTIVE);

		Instant afterUpdatedAt = null;
		UUID afterId = null;
		if (cursor != null && !cursor.isBlank()) {
			List<String> parts = CursorCodec.decode(cursor, 2);
			try {
				afterUpdatedAt = Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS);
				afterId = UUID.fromString(parts.get(1));
			} catch (RuntimeException e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
			}
		}
		List<NodeRow> rows = repository.workspaceNodes(workspaceId, statuses, types, tagId, afterUpdatedAt, afterId,
				size + 1);
		boolean hasMore = rows.size() > size;
		List<NodeRow> page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasMore) {
			NodeRow last = page.get(page.size() - 1);
			nextCursor = CursorCodec.encode(Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, last.updatedAt())),
					last.id().toString());
		}

		List<EdgeRow> edges = page.isEmpty() ? List.of()
				: repository.edgesAmong(workspaceId, page.stream().map(NodeRow::id).collect(Collectors.toList()), keys,
						HARD_MAX_EDGES + 1);
		boolean truncated = edges.size() > HARD_MAX_EDGES;
		if (truncated) {
			edges = edges.subList(0, HARD_MAX_EDGES);
		}
		return new GraphResponse(page.stream().map(n -> node(n, 0)).toList(), edges.stream().map(GraphService::edge).toList(),
				truncated, truncated ? "MAX_EDGES" : "NONE", applied(0, types, keys, includeArchived), List.of(),
				nextCursor, new Limits(size, HARD_MAX_EDGES));
	}

	// ---- helpers ---------------------------------------------------------------------------

	/** 이미 담은 Node와 같은 batch에서 먼저 나온 Node를 제외하고, 정렬 순서대로 Node당 한 번만 남긴다. */
	private static List<NeighborRow> firstPerNode(List<NeighborRow> rows, Map<UUID, GraphNode> included) {
		Map<UUID, NeighborRow> unique = new LinkedHashMap<>();
		for (NeighborRow row : rows) {
			if (!included.containsKey(row.node().id())) {
				unique.putIfAbsent(row.node().id(), row);
			}
		}
		return new ArrayList<>(unique.values());
	}

	private static void addCandidates(List<ExpansionCandidate> candidates, List<NeighborRow> rows) {
		for (NeighborRow row : rows) {
			if (candidates.size() >= MAX_CANDIDATES) {
				return;
			}
			candidates.add(new ExpansionCandidate(row.node().id(), row.node().title(), row.node().type().name(),
					row.relationKey()));
		}
	}

	/** 존재하지 않는 관계 타입 key는 조용히 빈 결과가 되지 않게 400으로 알린다. */
	private List<String> validRelationKeys(UUID userId, List<String> relationTypes) {
		if (relationTypes == null || relationTypes.isEmpty()) {
			return List.of();
		}
		Set<String> known = relationQueryService.listTypes(userId).stream()
				.map(t -> t.key())
				.collect(Collectors.toSet());
		for (String key : relationTypes) {
			if (!known.contains(key)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
						List.of(new ApiError.FieldError("relationTypes", "UNKNOWN_RELATION_TYPE")));
			}
		}
		return relationTypes.stream().distinct().toList();
	}

	private static void requireRange(String field, int value, int min, int max) {
		if (value < min) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError(field, "MIN_" + min)));
		}
		if (value > max) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "LIMIT_EXCEEDED", field + "은(는) 최대 " + max + "까지 가능합니다.",
					List.of(new ApiError.FieldError(field, "MAX_" + max)));
		}
	}

	private static AppliedFilters applied(int depth, List<NodeType> types, List<String> keys, boolean includeArchived) {
		return new AppliedFilters(depth, types.stream().map(Enum::name).toList(), keys, includeArchived);
	}

	private static GraphNode node(NodeRow row, int depth) {
		return new GraphNode(row.id(), row.type().name(), row.title(), row.status().name(), depth);
	}

	private static GraphEdge edge(EdgeRow row) {
		return new GraphEdge(row.id(), row.sourceId(), row.targetId(), row.key(), row.forwardLabel(), !row.symmetric());
	}
}
