package com.devgraph.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.web.KeysetPaging.Position;
import com.devgraph.knowledge.domain.NodeStatus;

/**
 * subtype(Error/Solution/Project/Resource) 목록의 id 선별. Node와 subtype 행을 조인해 `updatedAt DESC, id ASC` keyset으로
 * 고른다. subtype 엔티티는 클래스가 아니라 이름으로 받는다(모듈 순환 의존을 피하기 위해 Snippet·Relation과 같은 방식).
 * `extraWhere`는 **호출한 모듈이 쓴 상수 JPQL 조각**이어야 하며(별칭 `n`=Node, `x`=subtype) 사용자 입력은 항상 `params`로 넘긴다.
 */
@Service
public class SubtypeListQueryService {

	public record Row(UUID id, Instant updatedAt) {
	}

	@PersistenceContext
	private EntityManager entityManager;

	@Transactional(readOnly = true)
	public List<Row> find(UUID workspaceId, String subtypeEntity, NodeStatus status, String extraWhere,
			Map<String, Object> params, Position after, int limit) {
		StringBuilder jpql = new StringBuilder("select n.id, n.updatedAt from KnowledgeNodeJpaEntity n, ")
				.append(subtypeEntity)
				.append(" x where x.nodeId = n.id and n.workspaceId = :ws and n.status = :status");
		if (extraWhere != null && !extraWhere.isBlank()) {
			jpql.append(" and ").append(extraWhere);
		}
		if (after != null) {
			jpql.append(" and (n.updatedAt < :au or (n.updatedAt = :au and n.id > :aid))");
		}
		jpql.append(" order by n.updatedAt desc, n.id asc");
		Query query = entityManager.createQuery(jpql.toString()).setParameter("ws", workspaceId)
				.setParameter("status", status);
		params.forEach(query::setParameter);
		if (after != null) {
			query.setParameter("au", after.updatedAt()).setParameter("aid", after.id());
		}
		@SuppressWarnings("unchecked")
		List<Object[]> rows = query.setMaxResults(limit).getResultList();
		return rows.stream().map(r -> new Row((UUID) r[0], (Instant) r[1])).toList();
	}
}
