package com.devgraph.resource.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.relation.application.RelationViews.NodeRelations;
import com.devgraph.tag.application.TagView;

/** 설계서 §12.3/§17.4 Resource 응답. `duplicates`는 같은 URL의 다른 Resource(경고용, 저장은 막지 않는다). */
public final class ResourceViews {

	private ResourceViews() {
	}

	public record ResourceData(String url, String kind, String siteName, Instant lastCheckedAt) {
	}

	public record DuplicateRef(UUID id, String title) {
	}

	public record ResourceDetail(UUID id, String type, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt, ResourceData resource,
			List<DuplicateRef> duplicates, NodeRelations relations) {
	}

	public record ResourceSummary(UUID id, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant updatedAt, String url, String kind, String siteName) {
	}
}
