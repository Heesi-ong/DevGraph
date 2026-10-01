package com.devgraph.common.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * knowledge_nodes의 1:1 subtype 행(설계서 §11.2)의 공통 부분. PK가 애플리케이션이 정한 node id라 Persistable로
 * 신규 여부를 알려 저장 전 불필요한 SELECT(merge)를 피한다.
 */
@MappedSuperclass
public abstract class SubtypeRecord implements Persistable<UUID> {

	@Id
	@Column(name = "node_id")
	private UUID nodeId;

	@Column(name = "workspace_id", nullable = false, updatable = false)
	private UUID workspaceId;

	@Transient
	private boolean isNew;

	protected SubtypeRecord() {
	}

	protected SubtypeRecord(UUID nodeId, UUID workspaceId) {
		this.nodeId = nodeId;
		this.workspaceId = workspaceId;
		this.isNew = true;
	}

	@Override
	public UUID getId() {
		return nodeId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	void markNotNew() {
		this.isNew = false;
	}

	public UUID getNodeId() {
		return nodeId;
	}

	public UUID getWorkspaceId() {
		return workspaceId;
	}
}
