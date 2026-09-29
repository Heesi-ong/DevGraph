package com.devgraph.tag.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `tags`. 태그는 분류, Relation은 의미 연결에 쓴다(§9.2 KNOW-06). */
@Entity
@Table(name = "tags")
public class TagJpaEntity {

	@Id
	private UUID id;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(nullable = false)
	private String name;

	@Column(name = "normalized_name", nullable = false)
	private String normalizedName;

	private String color;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected TagJpaEntity() {
	}

	public TagJpaEntity(UUID id, UUID workspaceId, String name, String normalizedName, String color) {
		this.id = id;
		this.workspaceId = workspaceId;
		this.name = name;
		this.normalizedName = normalizedName;
		this.color = color;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public String getName() {
		return name;
	}

	public String getNormalizedName() {
		return normalizedName;
	}

	public String getColor() {
		return color;
	}

	public void update(String name, String normalizedName, String color) {
		this.name = name;
		this.normalizedName = normalizedName;
		this.color = color;
	}
}
