package com.devgraph.knowledge.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;

/**
 * 설계서 §9.2 KNOW-02 목록 조회. 정렬은 `updatedAt DESC, id ASC` 고정이고 keyset 조건은 방향별로 펼친다
 * (튜플 비교는 id만 방향이 반대라 부정확하다, §9.5와 같은 이유). 조건이 있을 때만 절을 붙인다.
 */
@Repository
public class KnowledgeNodeQueryRepository {

	@PersistenceContext
	private EntityManager entityManager;

	public List<KnowledgeNodeJpaEntity> search(UUID workspaceId, UUID userId, NodeStatus status, NodeType type,
			UUID tagId, boolean favoriteOnly, Instant afterUpdatedAt, UUID afterId, int limit) {
		StringBuilder jpql = new StringBuilder(
				"select n from KnowledgeNodeJpaEntity n where n.workspaceId = :ws and n.status = :status");
		if (type != null) {
			jpql.append(" and n.nodeType = :type");
		}
		if (tagId != null) {
			jpql.append(" and exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
		}
		if (favoriteOnly) {
			jpql.append(" and exists (select 1 from FavoriteJpaEntity f where f.nodeId = n.id and f.userId = :userId)");
		}
		if (afterUpdatedAt != null) {
			jpql.append(" and (n.updatedAt < :au or (n.updatedAt = :au and n.id > :aid))");
		}
		jpql.append(" order by n.updatedAt desc, n.id asc");

		TypedQuery<KnowledgeNodeJpaEntity> query = entityManager
				.createQuery(jpql.toString(), KnowledgeNodeJpaEntity.class)
				.setParameter("ws", workspaceId)
				.setParameter("status", status);
		if (type != null) {
			query.setParameter("type", type);
		}
		if (tagId != null) {
			query.setParameter("tagId", tagId);
		}
		if (favoriteOnly) {
			query.setParameter("userId", userId);
		}
		if (afterUpdatedAt != null) {
			query.setParameter("au", afterUpdatedAt).setParameter("aid", afterId);
		}
		return query.setMaxResults(limit).getResultList();
	}
}
