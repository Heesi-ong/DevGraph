package com.devgraph.knowledge.infrastructure;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** 설계서 §12.3 `favorites`. 사용자별 상태라 Node에 boolean으로 두지 않는다(§12.4). 쓰기는 repository의 native upsert가 한다. */
@Entity
@Table(name = "favorites")
@IdClass(FavoriteJpaEntity.Key.class)
public class FavoriteJpaEntity {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Id
	@Column(name = "node_id")
	private UUID nodeId;

	@Column(name = "workspace_id", nullable = false)
	private UUID workspaceId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected FavoriteJpaEntity() {
	}

	public UUID getNodeId() {
		return nodeId;
	}

	public static class Key implements Serializable {
		private UUID userId;
		private UUID nodeId;

		public Key() {
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key other && Objects.equals(userId, other.userId) && Objects.equals(nodeId, other.nodeId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userId, nodeId);
		}
	}
}
