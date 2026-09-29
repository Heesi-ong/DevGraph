package com.devgraph.relation.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 설계서 §12.3 `knowledge_relations`: 방향이 있는 인접 목록 edge. */
@Entity
@Table(name = "knowledge_relations")
public class RelationJpaEntity {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "workspace_id", nullable = false, updatable = false)
	private UUID workspaceId;

	@Column(name = "source_node_id", nullable = false)
	private UUID sourceNodeId;

	@Column(name = "target_node_id", nullable = false)
	private UUID targetNodeId;

	@Column(name = "relation_type_id", nullable = false)
	private UUID relationTypeId;

	private String note;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected RelationJpaEntity() {
	}

	public RelationJpaEntity(UUID workspaceId, UUID sourceNodeId, UUID targetNodeId, UUID relationTypeId, String note,
			UUID createdBy) {
		this.workspaceId = workspaceId;
		this.sourceNodeId = sourceNodeId;
		this.targetNodeId = targetNodeId;
		this.relationTypeId = relationTypeId;
		this.note = note;
		this.createdBy = createdBy;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.updatedAt = this.createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public UUID getSourceNodeId() {
		return sourceNodeId;
	}

	public UUID getTargetNodeId() {
		return targetNodeId;
	}

	public UUID getRelationTypeId() {
		return relationTypeId;
	}

	public String getNote() {
		return note;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	/** 타입 변경. 대칭 타입으로 바뀌면 호출자가 canonical 순서로 source/target을 넘긴다. */
	public void change(UUID sourceNodeId, UUID targetNodeId, UUID relationTypeId, String note) {
		this.sourceNodeId = sourceNodeId;
		this.targetNodeId = targetNodeId;
		this.relationTypeId = relationTypeId;
		this.note = note;
		this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
