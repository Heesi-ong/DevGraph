package com.devgraph.workspace.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMemberJpaEntity, WorkspaceMemberId> {

	List<WorkspaceMemberJpaEntity> findByUserId(UUID userId);
}
