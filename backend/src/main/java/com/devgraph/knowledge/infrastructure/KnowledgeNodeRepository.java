package com.devgraph.knowledge.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeNodeRepository extends JpaRepository<KnowledgeNodeJpaEntity, UUID> {

	/** 모든 조회는 workspace 조건을 포함한다(§17.1). 다른 Workspace의 id는 "없음"과 구분되지 않는다. */
	Optional<KnowledgeNodeJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
