package com.devgraph.auth.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §17.2.1 개별 Refresh Token rotation 이력. */
@Entity
@Table(name = "auth_sessions")
public class AuthSessionJpaEntity {

	@Id
	private UUID id;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "refresh_token_hash", nullable = false, unique = true)
	private byte[] refreshTokenHash;

	@Column(name = "user_agent_hash")
	private byte[] userAgentHash;

	@Column(name = "ip_prefix")
	private String ipPrefix;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "rotated_at")
	private Instant rotatedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AuthSessionJpaEntity() {
	}

	public AuthSessionJpaEntity(UUID id, UUID familyId, byte[] refreshTokenHash, byte[] userAgentHash,
			String ipPrefix, Instant expiresAt) {
		this.id = id;
		this.familyId = familyId;
		this.refreshTokenHash = refreshTokenHash;
		this.userAgentHash = userAgentHash;
		this.ipPrefix = ipPrefix;
		this.expiresAt = expiresAt;
		this.createdAt = Instant.now();
	}

	public UUID getId() {
		return id;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public byte[] getRefreshTokenHash() {
		return refreshTokenHash;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public String getIpPrefix() {
		return ipPrefix;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getRotatedAt() {
		return rotatedAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public void markRotated() {
		this.rotatedAt = Instant.now();
	}

	public void revoke() {
		this.revokedAt = Instant.now();
	}
}
