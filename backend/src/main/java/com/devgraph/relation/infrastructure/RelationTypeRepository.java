package com.devgraph.relation.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationTypeRepository extends JpaRepository<RelationTypeJpaEntity, UUID> {

	/** 시스템 타입(workspace_id IS NULL)과 해당 Workspace의 사용자 타입 중 활성인 것. */
	@Query("select t from RelationTypeJpaEntity t where t.active = true and (t.workspaceId is null or t.workspaceId = :ws) order by t.system desc, t.key")
	List<RelationTypeJpaEntity> findAvailable(@Param("ws") UUID workspaceId);

	@Query("select t from RelationTypeJpaEntity t where t.key = :key and t.workspaceId is null and t.active = true")
	Optional<RelationTypeJpaEntity> findSystemByKey(@Param("key") String key);

	@Query("select t from RelationTypeJpaEntity t where t.id = :id and t.active = true and (t.workspaceId is null or t.workspaceId = :ws)")
	Optional<RelationTypeJpaEntity> findAvailableById(@Param("id") UUID id, @Param("ws") UUID workspaceId);
}
