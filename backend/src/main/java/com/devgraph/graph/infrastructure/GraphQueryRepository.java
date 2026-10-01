package com.devgraph.graph.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;

/**
 * Graph 읽기 전용 조회. Node/Relation 엔티티는 클래스가 아니라 이름으로만 참조한다(다른 모듈 내부 타입에 의존하지 않기 위해).
 * 필터는 traversal 이후가 아니라 조회 조건에 포함한다(§13.3).
 */
@Repository
public class GraphQueryRepository {

	public record NodeRow(UUID id, NodeType type, NodeStatus status, String title, Instant updatedAt) {
	}

	/** frontier에서 한 걸음 떨어진 Node와 그 edge 끝점. */
	public record NeighborRow(NodeRow node, UUID sourceId, UUID targetId, String relationKey) {
	}

	public record EdgeRow(UUID id, UUID sourceId, UUID targetId, String key, String forwardLabel, boolean symmetric) {
	}

	@PersistenceContext
	private EntityManager entityManager;
	private final NamedParameterJdbcTemplate jdbc;

	public GraphQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public NodeRow findNode(UUID workspaceId, UUID nodeId) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createQuery("""
				select n.id, n.nodeType, n.status, n.title, n.updatedAt from KnowledgeNodeJpaEntity n
				where n.workspaceId = :ws and n.id = :id""")
				.setParameter("ws", workspaceId).setParameter("id", nodeId).getResultList();
		return rows.isEmpty() ? null : nodeRow(rows.get(0), 0);
	}

	/** 낮은 depth부터 BFS하기 위한 한 단계 확장. 정렬은 `updatedAt DESC, id ASC`로 잘림 지점을 결정적으로 만든다. */
	public List<NeighborRow> neighbors(UUID workspaceId, Collection<UUID> frontier, Collection<NodeStatus> statuses,
			Collection<NodeType> nodeTypes, Collection<String> relationKeys, Collection<UUID> visited, int limit) {
		// 먼저 Node별 대표 edge를 고른 뒤 limit한다. 중복 edge가 조회 상한을 소모하지 않게 한다.
		StringBuilder sql = new StringBuilder("""
				WITH ranked AS (
				  SELECT o.id, o.node_type, o.status, o.title, o.updated_at,
				         r.source_node_id, r.target_node_id, t.key,
				         row_number() OVER (PARTITION BY o.id ORDER BY r.id ASC) AS rn
				  FROM knowledge_relations r
				  JOIN relation_types t ON t.id = r.relation_type_id
				  JOIN knowledge_nodes o ON o.workspace_id = r.workspace_id
				    AND ((r.source_node_id IN (:frontier) AND o.id = r.target_node_id)
				      OR (r.target_node_id IN (:frontier) AND o.id = r.source_node_id))
				  WHERE r.workspace_id = :ws AND o.status IN (:statuses) AND o.id NOT IN (:visited)
				""");
		if (!nodeTypes.isEmpty()) {
			sql.append(" AND o.node_type IN (:nodeTypes)");
		}
		if (!relationKeys.isEmpty()) {
			sql.append(" AND t.key IN (:relationKeys)");
		}
		sql.append(") SELECT * FROM ranked WHERE rn = 1 ORDER BY updated_at DESC, id ASC LIMIT :limit");
		MapSqlParameterSource params = new MapSqlParameterSource("ws", workspaceId)
				.addValue("frontier", frontier).addValue("visited", visited).addValue("limit", limit)
				.addValue("statuses", statuses.stream().map(Enum::name).toList());
		if (!nodeTypes.isEmpty()) {
			params.addValue("nodeTypes", nodeTypes.stream().map(Enum::name).toList());
		}
		if (!relationKeys.isEmpty()) {
			params.addValue("relationKeys", relationKeys);
		}
		return jdbc.query(sql.toString(), params, (rs, i) -> new NeighborRow(new NodeRow(
				rs.getObject("id", UUID.class), NodeType.valueOf(rs.getString("node_type")),
				NodeStatus.valueOf(rs.getString("status")), rs.getString("title"),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant()),
				rs.getObject("source_node_id", UUID.class), rs.getObject("target_node_id", UUID.class), rs.getString("key")));
	}

	/** 주어진 Node 집합 **안쪽**의 edge만(양 끝이 모두 집합에 속한 것). 생성 순서로 고정한다. */
	public List<EdgeRow> edgesAmong(UUID workspaceId, Collection<UUID> nodeIds, Collection<String> relationKeys,
			int limit) {
		StringBuilder jpql = new StringBuilder("""
				select r.id, r.sourceNodeId, r.targetNodeId, t.key, t.forwardLabel, t.directionality
				from RelationJpaEntity r, RelationTypeJpaEntity t
				where r.workspaceId = :ws and r.relationTypeId = t.id
				  and r.sourceNodeId in :ids and r.targetNodeId in :ids""");
		if (!relationKeys.isEmpty()) {
			jpql.append(" and t.key in :relationKeys");
		}
		jpql.append(" order by r.createdAt asc, r.id asc");
		Query query = entityManager.createQuery(jpql.toString()).setParameter("ws", workspaceId)
				.setParameter("ids", nodeIds);
		if (!relationKeys.isEmpty()) {
			query.setParameter("relationKeys", relationKeys);
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.setMaxResults(limit).getResultList();
		return rows.stream().map(r -> new EdgeRow((UUID) r[0], (UUID) r[1], (UUID) r[2], (String) r[3], (String) r[4],
				"symmetric".equals(r[5]))).toList();
	}

	/** Workspace Graph용 Node 목록(§13.3: traversal이 아니라 필터링된 일반 목록 pagination). */
	public List<NodeRow> workspaceNodes(UUID workspaceId, Collection<NodeStatus> statuses,
			Collection<NodeType> nodeTypes, UUID tagId, Instant afterUpdatedAt, UUID afterId, int limit) {
		StringBuilder jpql = new StringBuilder("""
				select n.id, n.nodeType, n.status, n.title, n.updatedAt from KnowledgeNodeJpaEntity n
				where n.workspaceId = :ws and n.status in :statuses""");
		if (!nodeTypes.isEmpty()) {
			jpql.append(" and n.nodeType in :nodeTypes");
		}
		if (tagId != null) {
			jpql.append(" and exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
		}
		if (afterUpdatedAt != null) {
			jpql.append(" and (n.updatedAt < :au or (n.updatedAt = :au and n.id > :aid))");
		}
		jpql.append(" order by n.updatedAt desc, n.id asc");
		Query query = entityManager.createQuery(jpql.toString()).setParameter("ws", workspaceId)
				.setParameter("statuses", statuses);
		if (!nodeTypes.isEmpty()) {
			query.setParameter("nodeTypes", nodeTypes);
		}
		if (tagId != null) {
			query.setParameter("tagId", tagId);
		}
		if (afterUpdatedAt != null) {
			query.setParameter("au", afterUpdatedAt).setParameter("aid", afterId);
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.setMaxResults(limit).getResultList();
		return rows.stream().map(r -> nodeRow(r, 0)).toList();
	}

	private static NodeRow nodeRow(Object[] r, int offset) {
		return new NodeRow((UUID) r[offset], (NodeType) r[offset + 1], (NodeStatus) r[offset + 2],
				(String) r[offset + 3], (Instant) r[offset + 4]);
	}
}
