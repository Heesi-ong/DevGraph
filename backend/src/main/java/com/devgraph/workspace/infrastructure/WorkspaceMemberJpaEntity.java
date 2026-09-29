package com.devgraph.workspace.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** 설계서 §12.3 `workspace_members`. MVP는 가입 시 OWNER 1행만 생성한다. */
@Entity
@Table(name = "workspace_members")
@IdClass(WorkspaceMemberId.class)
public class WorkspaceMemberJpaEntity {

	@Id
	@Column(name = "workspace_id")
	private UUID workspaceId;

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(nullable = false)
	private String role = "OWNER";

	@Column(name = "joined_at", nullable = false)
	private Instant joinedAt;

	protected WorkspaceMemberJpaEntity() {
	}

	public WorkspaceMemberJpaEntity(UUID workspaceId, UUID userId) {
		this.workspaceId = workspaceId;
		this.userId = userId;
		this.joinedAt = Instant.now();
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public UUID getUserId() {
		return userId;
	}
}
