package com.devgraph.knowledge.infrastructure;

import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * 설계서 §12.3 `node_tags`. Node가 어떤 태그로 분류됐는지 나타내는 연결이라 knowledge 모듈이 소유하고,
 * 태그 사전(`tags`) 자체는 tag 모듈이 소유한다.
 */
@Entity
@Table(name = "node_tags")
@IdClass(NodeTagJpaEntity.Key.class)
public class NodeTagJpaEntity {

	@Id
	@Column(name = "node_id")
	private UUID nodeId;

	@Id
	@Column(name = "tag_id")
	private UUID tagId;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected NodeTagJpaEntity() {
	}

	public NodeTagJpaEntity(UUID workspaceId, UUID nodeId, UUID tagId) {
		this.workspaceId = workspaceId;
		this.nodeId = nodeId;
		this.tagId = tagId;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getNodeId() {
		return nodeId;
	}

	public UUID getTagId() {
		return tagId;
	}

	public static class Key implements Serializable {
		private UUID nodeId;
		private UUID tagId;

		public Key() {
		}

		public Key(UUID nodeId, UUID tagId) {
			this.nodeId = nodeId;
			this.tagId = tagId;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && Objects.equals(nodeId, other.nodeId) && Objects.equals(tagId, other.tagId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(nodeId, tagId);
		}
	}
}
