package com.devgraph.tag.infrastructure;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.devgraph.common.web.LikeEscape;

/** 태그 자동완성 목록. 조건이 있을 때만 절을 붙여 null 파라미터 타입 추론 문제를 피한다. */
@Repository
public class TagQueryRepository {

	@PersistenceContext
	private EntityManager entityManager;

	/** normalized_name 오름차순 keyset. afterName/afterId가 null이면 첫 페이지. */
	public List<TagJpaEntity> search(UUID workspaceId, String normalizedPrefix, String afterName, UUID afterId,
			int limit) {
		StringBuilder jpql = new StringBuilder("select t from TagJpaEntity t where t.workspaceId = :ws");
		if (normalizedPrefix != null) {
			jpql.append(" and t.normalizedName like :prefix escape '!'");
		}
		if (afterName != null) {
			jpql.append(" and (t.normalizedName > :afterName or (t.normalizedName = :afterName and t.id > :afterId))");
		}
		jpql.append(" order by t.normalizedName asc, t.id asc");

		TypedQuery<TagJpaEntity> query = entityManager.createQuery(jpql.toString(), TagJpaEntity.class)
				.setParameter("ws", workspaceId);
		if (normalizedPrefix != null) {
			query.setParameter("prefix", LikeEscape.prefix(normalizedPrefix));
		}
		if (afterName != null) {
			query.setParameter("afterName", afterName).setParameter("afterId", afterId);
		}
		return query.setMaxResults(limit).getResultList();
	}
}
