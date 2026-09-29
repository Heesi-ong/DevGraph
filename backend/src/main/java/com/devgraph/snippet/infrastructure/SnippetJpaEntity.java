package com.devgraph.snippet.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * 설계서 §12.3 `snippets`. PK가 애플리케이션이 정한 node id라 Persistable로 신규 여부를 알려
 * 저장 전 불필요한 SELECT(merge)를 피한다.
 * `use_count`/`last_used_at`은 동시 갱신 손실을 피하려고 JPA로 쓰지 않고 원자적 UPDATE로만 바꾼다.
 */
@Entity
@Table(name = "snippets")
public class SnippetJpaEntity implements Persistable<UUID> {

	@Id
	@Column(name = "node_id")
	private UUID nodeId;

	@Column(name = "workspace_id", nullable = false, updatable = false)
	private UUID workspaceId;

	@Column(nullable = false)
	private String language;

	private String framework;

	@Column(name = "current_version_no", nullable = false)
	private int currentVersionNo;

	@Column(name = "last_used_at", insertable = false, updatable = false)
	private Instant lastUsedAt;

	@Column(name = "use_count", insertable = false, updatable = false)
	private long useCount;

	@Column(name = "secret_scan_status", nullable = false)
	private String secretScanStatus;

	@Transient
	private boolean isNew;

	protected SnippetJpaEntity() {
	}

	public SnippetJpaEntity(UUID nodeId, UUID workspaceId, String language, String framework, int currentVersionNo,
			String secretScanStatus) {
		this.nodeId = nodeId;
		this.workspaceId = workspaceId;
		this.language = language;
		this.framework = framework;
		this.currentVersionNo = currentVersionNo;
		this.secretScanStatus = secretScanStatus;
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

	public String getLanguage() {
		return language;
	}

	public String getFramework() {
		return framework;
	}

	public int getCurrentVersionNo() {
		return currentVersionNo;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

	public long getUseCount() {
		return useCount;
	}

	public String getSecretScanStatus() {
		return secretScanStatus;
	}

	public void changeLanguage(String language, String framework) {
		this.language = language;
		this.framework = framework;
	}

	public void advanceVersion(String secretScanStatus) {
		this.currentVersionNo += 1;
		this.secretScanStatus = secretScanStatus;
	}
}
