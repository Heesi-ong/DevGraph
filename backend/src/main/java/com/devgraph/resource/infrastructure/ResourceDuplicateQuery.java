package com.devgraph.resource.infrastructure;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

import com.devgraph.knowledge.domain.NodeStatus;

/** 같은 정규화 URL을 가진 다른 Resource(휴지통 제외). Node 엔티티는 이름으로만 참조한다. */
@Repository
public class ResourceDuplicateQuery {

	public record Duplicate(UUID id, String title) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	public List<Duplicate> find(UUID workspaceId, String urlNormalized, UUID excludeNodeId) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createQuery("""
				select n.id, n.title from ResourceJpaEntity x, KnowledgeNodeJpaEntity n
				where x.nodeId = n.id and n.workspaceId = :ws and x.urlNormalized = :url
				  and n.status <> :trashed and n.id <> :self
				order by n.updatedAt desc, n.id asc""")
				.setParameter("ws", workspaceId).setParameter("url", urlNormalized)
				.setParameter("trashed", NodeStatus.TRASHED).setParameter("self", excludeNodeId)
				.setMaxResults(10).getResultList();
		return rows.stream().map(r -> new Duplicate((UUID) r[0], (String) r[1])).toList();
	}
}
