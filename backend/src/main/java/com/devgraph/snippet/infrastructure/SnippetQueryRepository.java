package com.devgraph.snippet.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.springframework.stereotype.Repository;

import com.devgraph.knowledge.domain.NodeStatus;

/**
 * Snippet 목록의 id 선별. 정렬은 Node와 같은 `updatedAt DESC, id ASC` keyset이다(§14.7).
 * Node 엔티티는 클래스가 아니라 이름으로만 참조해 knowledge 모듈 내부 타입에 의존하지 않는다 —
 * 화면용 필드는 이 id 목록을 knowledge 응용 서비스에 넘겨 채운다.
 */
@Repository
public class SnippetQueryRepository {

	public record Row(UUID nodeId, Instant updatedAt) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	public List<Row> search(UUID workspaceId, UUID userId, NodeStatus status, String language, String framework,
			UUID tagId, boolean favoriteOnly, Instant afterUpdatedAt, UUID afterId, int limit) {
		StringBuilder jpql = new StringBuilder("""
				select n.id, n.updatedAt from KnowledgeNodeJpaEntity n, SnippetJpaEntity s
				where s.nodeId = n.id and n.workspaceId = :ws and n.status = :status""");
		if (language != null) {
			jpql.append(" and s.language = :language");
		}
		if (framework != null) {
			jpql.append(" and s.framework = :framework");
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

		Query query = entityManager.createQuery(jpql.toString())
				.setParameter("ws", workspaceId)
				.setParameter("status", status);
		if (language != null) {
			query.setParameter("language", language);
		}
		if (framework != null) {
			query.setParameter("framework", framework);
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
		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.setMaxResults(limit).getResultList();
		return rows.stream().map(r -> new Row((UUID) r[0], (Instant) r[1])).toList();
	}
}
