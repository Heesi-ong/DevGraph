package com.devgraph.auth.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `users`. */
@Entity
@Table(name = "users")
public class UserJpaEntity {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(name = "email_normalized", nullable = false, unique = true)
	private String emailNormalized;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private UserStatus status = UserStatus.ACTIVE;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword = false;

	@Column(name = "password_changed_at")
	private Instant passwordChangedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deletion_requested_at")
	private Instant deletionRequestedAt;

	protected UserJpaEntity() {
	}

	public UserJpaEntity(UUID id, String email, String emailNormalized, String displayName, String passwordHash) {
		this.id = id;
		this.email = email;
		this.emailNormalized = emailNormalized;
		this.displayName = displayName;
		this.passwordHash = passwordHash;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getEmailNormalized() {
		return emailNormalized;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
		this.passwordChangedAt = Instant.now();
		this.updatedAt = Instant.now();
	}

	public UserStatus getStatus() {
		return status;
	}

	public void setStatus(UserStatus status) {
		this.status = status;
		this.updatedAt = Instant.now();
	}

	public boolean isMustChangePassword() {
		return mustChangePassword;
	}

	public void setMustChangePassword(boolean mustChangePassword) {
		this.mustChangePassword = mustChangePassword;
	}

	public Instant getDeletionRequestedAt() {
		return deletionRequestedAt;
	}

	public void setDeletionRequestedAt(Instant deletionRequestedAt) {
		this.deletionRequestedAt = deletionRequestedAt;
	}
}
