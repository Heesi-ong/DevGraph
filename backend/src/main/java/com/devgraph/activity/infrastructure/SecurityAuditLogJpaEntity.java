package com.devgraph.activity.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `security_audit_logs`. `activity_logs`와 분리해 REQUIRES_NEW로 즉시 커밋한다. */
@Entity
@Table(name = "security_audit_logs")
public class SecurityAuditLogJpaEntity {

	@Id
	private UUID id;

	@Column(name = "workspace_id")
	private UUID workspaceId;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "event_type", nullable = false)
	private String eventType;

	@Column(name = "ip_prefix")
	private String ipPrefix;

	@Column(nullable = false)
	private String outcome;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SecurityAuditLogJpaEntity() {
	}

	public SecurityAuditLogJpaEntity(UUID id, UUID actorUserId, String eventType, String outcome, String ipPrefix) {
		this.id = id;
		this.actorUserId = actorUserId;
		this.eventType = eventType;
		this.outcome = outcome;
		this.ipPrefix = ipPrefix;
		this.createdAt = Instant.now();
	}
}
