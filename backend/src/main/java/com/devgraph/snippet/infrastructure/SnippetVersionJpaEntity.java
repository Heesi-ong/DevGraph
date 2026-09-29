package com.devgraph.snippet.infrastructure;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 설계서 §12.3 `snippet_versions`. 불변이다 — 수정/삭제 경로가 없고 Hibernate도 갱신하지 않는다. */
@Entity
@Immutable
@Table(name = "snippet_versions")
public class SnippetVersionJpaEntity {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "workspace_id", nullable = false, updatable = false)
	private UUID workspaceId;

	@Column(name = "snippet_node_id", nullable = false, updatable = false)
	private UUID snippetNodeId;

	@Column(name = "version_no", nullable = false, updatable = false)
	private int versionNo;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(name = "change_summary", updatable = false)
	private String changeSummary;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "content_hash", nullable = false, updatable = false, length = 64)
	private String contentHash;

	@Column(name = "created_by", nullable = false, updatable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SnippetVersionJpaEntity() {
	}

	public SnippetVersionJpaEntity(UUID workspaceId, UUID snippetNodeId, int versionNo, String code,
			String changeSummary, String contentHash, UUID createdBy) {
		this.workspaceId = workspaceId;
		this.snippetNodeId = snippetNodeId;
		this.versionNo = versionNo;
		this.code = code;
		this.changeSummary = changeSummary;
		this.contentHash = contentHash;
		this.createdBy = createdBy;
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public int getVersionNo() {
		return versionNo;
	}

	public String getCode() {
		return code;
	}

	public String getChangeSummary() {
		return changeSummary;
	}

	public String getContentHash() {
		return contentHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
