package com.devgraph.project.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRecordRepository extends JpaRepository<ProjectRecordJpaEntity, UUID> {

	Optional<ProjectRecordJpaEntity> findByNodeIdAndWorkspaceId(UUID nodeId, UUID workspaceId);

	List<ProjectRecordJpaEntity> findByNodeIdIn(Collection<UUID> nodeIds);
}
