package com.devgraph.activity.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `activity_logs`. 본문/secret은 저장하지 않고 id와 action만 남긴다(§9.8). */
@Entity
@Table(name = "activity_logs")
public class ActivityLogJpaEntity {

	@Id
	private UUID id;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "actor_user_id", nullable = false)
	private UUID actorUserId;

	@Column(nullable = false)
	private String action;

	@Column(name = "object_type", nullable = false)
	private String objectType;

	@Column(name = "object_id", nullable = false)
	private UUID objectId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ActivityLogJpaEntity() {
	}

	public ActivityLogJpaEntity(UUID workspaceId, UUID actorUserId, String action, String objectType, UUID objectId) {
		this.id = UUID.randomUUID();
		this.workspaceId = workspaceId;
		this.actorUserId = actorUserId;
		this.action = action;
		this.objectType = objectType;
		this.objectId = objectId;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
