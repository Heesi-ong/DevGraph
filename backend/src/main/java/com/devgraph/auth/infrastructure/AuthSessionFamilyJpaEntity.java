package com.devgraph.auth.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 설계서 §17.2.1 기기 세션의 안정 식별자. Access JWT의 sid claim이 가리키는 대상이다.
 * rotation row({@link AuthSessionJpaEntity})와 분리해, 정상 refresh가 세션을 깨지 않게 한다.
 */
@Entity
@Table(name = "auth_session_families")
public class AuthSessionFamilyJpaEntity {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "device_label")
	private String deviceLabel;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_rotated_at", nullable = false)
	private Instant lastRotatedAt;

	@Column(name = "absolute_expires_at", nullable = false)
	private Instant absoluteExpiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoke_reason")
	private RevokeReason revokeReason;

	protected AuthSessionFamilyJpaEntity() {
	}

	public AuthSessionFamilyJpaEntity(UUID id, UUID userId, String deviceLabel, Instant absoluteExpiresAt) {
		this.id = id;
		this.userId = userId;
		this.deviceLabel = deviceLabel;
		this.absoluteExpiresAt = absoluteExpiresAt;
		Instant now = Instant.now();
		this.createdAt = now;
		this.lastRotatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getDeviceLabel() {
		return deviceLabel;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getLastRotatedAt() {
		return lastRotatedAt;
	}

	public Instant getAbsoluteExpiresAt() {
		return absoluteExpiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isAbsoluteExpired() {
		return Instant.now().isAfter(absoluteExpiresAt);
	}

	public void touchRotation() {
		this.lastRotatedAt = Instant.now();
	}

	public void revoke(RevokeReason reason) {
		this.revokedAt = Instant.now();
		this.revokeReason = reason;
	}
}
