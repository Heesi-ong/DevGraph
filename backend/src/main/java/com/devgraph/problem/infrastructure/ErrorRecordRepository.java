package com.devgraph.problem.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorRecordRepository extends JpaRepository<ErrorRecordJpaEntity, UUID> {

	Optional<ErrorRecordJpaEntity> findByNodeIdAndWorkspaceId(UUID nodeId, UUID workspaceId);

	List<ErrorRecordJpaEntity> findByNodeIdIn(Collection<UUID> nodeIds);
}
