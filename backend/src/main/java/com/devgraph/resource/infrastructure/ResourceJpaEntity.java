package com.devgraph.resource.infrastructure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.devgraph.common.persistence.SubtypeRecord;

/** 설계서 §12.3 `resources`. */
@Entity
@Table(name = "resources")
public class ResourceJpaEntity extends SubtypeRecord {

	@Column(nullable = false)
	private String url;

	@Column(name = "url_normalized", nullable = false)
	private String urlNormalized;

	@Column(name = "resource_kind", nullable = false)
	private String resourceKind;

	@Column(name = "site_name")
	private String siteName;

	@Column(name = "last_checked_at")
	private Instant lastCheckedAt;

	protected ResourceJpaEntity() {
	}

	public ResourceJpaEntity(UUID nodeId, UUID workspaceId, String url, String urlNormalized, String resourceKind,
			String siteName) {
		super(nodeId, workspaceId);
		edit(url, urlNormalized, resourceKind, siteName);
	}

	public String getUrl() {
		return url;
	}

	public String getUrlNormalized() {
		return urlNormalized;
	}

	public String getResourceKind() {
		return resourceKind;
	}

	public String getSiteName() {
		return siteName;
	}

	public Instant getLastCheckedAt() {
		return lastCheckedAt;
	}

	public final void edit(String url, String urlNormalized, String resourceKind, String siteName) {
		this.url = url;
		this.urlNormalized = urlNormalized;
		this.resourceKind = resourceKind;
		this.siteName = siteName;
	}
}
