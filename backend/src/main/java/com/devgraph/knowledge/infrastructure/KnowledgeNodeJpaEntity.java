package com.devgraph.knowledge.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;

/** 설계서 §12.3 `knowledge_nodes`. `version`은 optimistic locking(§9.2 KNOW-03)에 쓴다. */
@Entity
@Table(name = "knowledge_nodes")
public class KnowledgeNodeJpaEntity {

	@Id
	private UUID id;

	@Column(name = "workspace_id", nullable = false, updatable = false)
	private UUID workspaceId;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "node_type", nullable = false, updatable = false)
	private NodeType nodeType;

	@Column(nullable = false)
	private String title;

	private String summary;

	@Column(name = "body_md")
	private String bodyMd;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NodeStatus status = NodeStatus.ACTIVE;

	// 래퍼 타입이어야 Spring Data가 신규 엔티티를 판별(version == null)해 불필요한 SELECT(merge)를 하지 않는다.
	@Version
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "trashed_at")
	private Instant trashedAt;

	protected KnowledgeNodeJpaEntity() {
	}

	public KnowledgeNodeJpaEntity(UUID id, UUID workspaceId, UUID createdBy, NodeType nodeType, String title,
			String summary, String bodyMd) {
		this.id = id;
		this.workspaceId = workspaceId;
		this.createdBy = createdBy;
		this.nodeType = nodeType;
		this.title = title;
		this.summary = summary;
		this.bodyMd = bodyMd;
		this.createdAt = now();
		this.updatedAt = this.createdAt;
	}

	// DB(timestamptz)는 마이크로초 정밀도다. keyset cursor 비교가 어긋나지 않도록 미리 맞춰 둔다.
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}

	public NodeType getNodeType() {
		return nodeType;
	}

	public String getTitle() {
		return title;
	}

	public String getSummary() {
		return summary;
	}

	public String getBodyMd() {
		return bodyMd;
	}

	public NodeStatus getStatus() {
		return status;
	}

	public Long getVersion() {
		return version;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void edit(String title, String summary, String bodyMd) {
		this.title = title;
		this.summary = summary;
		this.bodyMd = bodyMd;
		touch();
	}

	/** 내용은 그대로여도 태그처럼 연관 데이터가 바뀐 경우 version/updatedAt을 올리기 위해 쓴다. */
	public void touch() {
		this.updatedAt = now();
	}

	public void changeStatus(NodeStatus target) {
		this.status = target;
		Instant at = now();
		this.updatedAt = at;
		this.archivedAt = target == NodeStatus.ARCHIVED ? at : null;
		this.trashedAt = target == NodeStatus.TRASHED ? at : null;
	}
}
