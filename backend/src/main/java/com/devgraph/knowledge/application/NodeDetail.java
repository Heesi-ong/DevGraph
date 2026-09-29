package com.devgraph.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.relation.application.RelationViews.NodeRelations;
import com.devgraph.tag.application.TagView;

/**
 * 설계서 §14.7 NodeDetail. `relations`는 이 Node를 기준으로 본 outgoing/incoming 관계(§13.1)다.
 */
public record NodeDetail(
		UUID id,
		String type,
		String title,
		String summary,
		String bodyMd,
		String status,
		long version,
		List<TagView> tags,
		boolean favorite,
		Instant createdAt,
		Instant updatedAt,
		NodeRelations relations
) {
}
