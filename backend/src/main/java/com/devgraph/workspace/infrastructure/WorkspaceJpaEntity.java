package com.devgraph.workspace.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `workspaces`. 개인용에서도 Team 확장을 대비해 명시적으로 둔다. */
@Entity
@Table(name = "workspaces")
public class WorkspaceJpaEntity {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false, unique = true)
	private String slug;

	@Column(nullable = false)
	private String plan = "PERSONAL";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected WorkspaceJpaEntity() {
	}

	public WorkspaceJpaEntity(UUID id, String name, String slug) {
		this.id = id;
		this.name = name;
		this.slug = slug;
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}
}
