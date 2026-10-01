package com.devgraph.relation.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;

import com.devgraph.common.web.LikeEscape;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;

/**
 * Relation이 필요로 하는 Node 정보 조회. knowledge 모듈의 JPA 클래스를 import하지 않고 엔티티 이름으로만
 * 참조해(SnippetQueryRepository와 같은 방식) 모듈 사이 순환 의존을 만들지 않는다 —
 * knowledge가 Relation을 조회(NodeDetail.relations)하기 때문이다.
 */
@Repository
public class RelationQueryRepository {

	public record NodeRef(UUID id, NodeType type, NodeStatus status, String title) {
	}

	/** 한 Node 기준으로 본 edge 한 줄. `other*`는 반대쪽 Node다. */
	public record Row(UUID relationId, UUID sourceId, UUID targetId, UUID typeId, String typeKey,
			String forwardLabel, String inverseLabel, boolean symmetric, String note, Instant createdAt,
			UUID otherId, String otherTitle, NodeType otherType, NodeStatus otherStatus) {
	}

	public record Candidate(UUID id, String title, NodeType type, NodeStatus status) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	public List<NodeRef> findNodes(UUID workspaceId, Collection<UUID> ids) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createQuery("""
				select n.id, n.nodeType, n.status, n.title from KnowledgeNodeJpaEntity n
				where n.workspaceId = :ws and n.id in :ids""")
				.setParameter("ws", workspaceId).setParameter("ids", ids).getResultList();
		return rows.stream()
				.map(r -> new NodeRef((UUID) r[0], (NodeType) r[1], (NodeStatus) r[2], (String) r[3]))
				.toList();
	}

	/** 휴지통 Node와 이어진 edge는 제외한다. 최신 관계 먼저, 동률은 id로 고정. */
	public List<Row> findForNode(UUID workspaceId, UUID nodeId, int limit) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createQuery("""
				select r.id, r.sourceNodeId, r.targetNodeId, t.id, t.key, t.forwardLabel, t.inverseLabel,
				       t.directionality, r.note, r.createdAt, o.id, o.title, o.nodeType, o.status
				from RelationJpaEntity r, RelationTypeJpaEntity t, KnowledgeNodeJpaEntity o
				where r.relationTypeId = t.id and r.workspaceId = :ws and o.workspaceId = :ws
				  and ((r.sourceNodeId = :n and o.id = r.targetNodeId) or (r.targetNodeId = :n and o.id = r.sourceNodeId))
				  and o.status <> :trashed
				order by r.createdAt desc, r.id asc""")
				.setParameter("ws", workspaceId).setParameter("n", nodeId).setParameter("trashed", NodeStatus.TRASHED)
				.setMaxResults(limit).getResultList();
		return rows.stream().map(r -> new Row((UUID) r[0], (UUID) r[1], (UUID) r[2], (UUID) r[3], (String) r[4],
				(String) r[5], (String) r[6], "symmetric".equals(r[7]), (String) r[8], (Instant) r[9], (UUID) r[10],
				(String) r[11], (NodeType) r[12], (NodeStatus) r[13])).toList();
	}

	/**
	 * 주어진 Node들에서 특정 관계 key로 나가는(outgoing) 또는 들어오는 edge 수. 휴지통 Node와 이어진 것은 제외한다.
	 * Problems 목록의 체인 미리보기(해결 수, 프로젝트 수)에 쓴다.
	 */
	public java.util.Map<UUID, Long> countLinks(UUID workspaceId, Collection<UUID> nodeIds, String typeKey,
			boolean outgoing) {
		if (nodeIds.isEmpty()) {
			return java.util.Map.of();
		}
		String self = outgoing ? "r.sourceNodeId" : "r.targetNodeId";
		String other = outgoing ? "r.targetNodeId" : "r.sourceNodeId";
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createQuery("select " + self + ", count(r) from RelationJpaEntity r, "
				+ "RelationTypeJpaEntity t, KnowledgeNodeJpaEntity o where r.relationTypeId = t.id and t.key = :key "
				+ "and r.workspaceId = :ws and o.workspaceId = :ws and o.id = " + other + " and o.status <> :trashed "
				+ "and " + self + " in :ids group by " + self)
				.setParameter("key", typeKey).setParameter("ws", workspaceId).setParameter("ids", nodeIds)
				.setParameter("trashed", NodeStatus.TRASHED).getResultList();
		java.util.Map<UUID, Long> counts = new java.util.HashMap<>();
		rows.forEach(r -> counts.put((UUID) r[0], (Long) r[1]));
		return counts;
	}

	public enum Side { OUTGOING, INCOMING }

	/**
	 * Relation Picker 후보: 이 타입·방향으로 허용되는 타입의 Node 중 아직 연결되지 않은 것.
	 * 제목 검색은 통합 검색(Phase 5) 이전의 최소 기능이라 부분 일치(대소문자 무시)만 한다.
	 */
	public List<Candidate> findCandidates(UUID workspaceId, UUID selfId, UUID typeId, Side side, boolean symmetric,
			Collection<NodeType> allowedTypes, String titleQuery, int limit) {
		String existing;
		if (symmetric) {
			existing = "(r.sourceNodeId = :self and r.targetNodeId = n.id) or (r.sourceNodeId = n.id and r.targetNodeId = :self)";
		} else if (side == Side.OUTGOING) {
			existing = "r.sourceNodeId = :self and r.targetNodeId = n.id";
		} else {
			existing = "r.sourceNodeId = n.id and r.targetNodeId = :self";
		}
		StringBuilder jpql = new StringBuilder("""
				select n.id, n.title, n.nodeType, n.status from KnowledgeNodeJpaEntity n
				where n.workspaceId = :ws and n.id <> :self and n.status <> :trashed and n.nodeType in :allowed""");
		if (titleQuery != null) {
			jpql.append(" and lower(n.title) like :q escape '!'");
		}
		jpql.append(" and not exists (select 1 from RelationJpaEntity r where r.workspaceId = :ws and r.relationTypeId = :typeId and (")
				.append(existing).append("))");
		jpql.append(" order by n.updatedAt desc, n.id asc");
		Query query = entityManager.createQuery(jpql.toString())
				.setParameter("ws", workspaceId).setParameter("self", selfId).setParameter("typeId", typeId)
				.setParameter("trashed", NodeStatus.TRASHED).setParameter("allowed", allowedTypes);
		if (titleQuery != null) {
			query.setParameter("q", LikeEscape.contains(titleQuery.toLowerCase(java.util.Locale.ROOT)));
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.setMaxResults(limit).getResultList();
		return rows.stream()
				.map(r -> new Candidate((UUID) r[0], (String) r[1], (NodeType) r[2], (NodeStatus) r[3]))
				.toList();
	}
}
