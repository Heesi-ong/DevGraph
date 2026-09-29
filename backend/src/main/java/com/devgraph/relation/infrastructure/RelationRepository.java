package com.devgraph.relation.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationRepository extends JpaRepository<RelationJpaEntity, UUID> {

	/** 모든 조회는 workspace 조건을 포함한다(§17.1). */
	Optional<RelationJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

	Optional<RelationJpaEntity> findByWorkspaceIdAndSourceNodeIdAndRelationTypeIdAndTargetNodeId(UUID workspaceId,
			UUID sourceNodeId, UUID relationTypeId, UUID targetNodeId);
}
