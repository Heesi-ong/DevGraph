package com.devgraph.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.tag.application.TagView;

/** 목록/상태 전이 응답. 본문(최대 1MB)은 목록 payload를 키우므로 싣지 않는다. */
public record NodeSummary(
		UUID id,
		String type,
		String title,
		String summary,
		String status,
		long version,
		List<TagView> tags,
		boolean favorite,
		Instant createdAt,
		Instant updatedAt
) {
}
