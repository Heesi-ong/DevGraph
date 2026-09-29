package com.devgraph.workspace.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {

	boolean existsBySlug(String slug);
}
