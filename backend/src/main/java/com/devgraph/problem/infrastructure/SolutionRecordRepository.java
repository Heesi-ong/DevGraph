package com.devgraph.problem.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SolutionRecordRepository extends JpaRepository<SolutionRecordJpaEntity, UUID> {

	Optional<SolutionRecordJpaEntity> findByNodeIdAndWorkspaceId(UUID nodeId, UUID workspaceId);

	List<SolutionRecordJpaEntity> findByNodeIdIn(Collection<UUID> nodeIds);
}
