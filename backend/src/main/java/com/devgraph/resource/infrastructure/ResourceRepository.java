package com.devgraph.resource.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<ResourceJpaEntity, UUID> {

	Optional<ResourceJpaEntity> findByNodeIdAndWorkspaceId(UUID nodeId, UUID workspaceId);

	List<ResourceJpaEntity> findByNodeIdIn(Collection<UUID> nodeIds);
}
