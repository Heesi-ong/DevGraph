package com.devgraph.knowledge.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.tag.application.TagView;

/**
 * 설계서 §14.7 NodeDetail. Relation(`relations.outgoing/incoming`)은 Relation을 구현하는 Phase 4에서
 * 추가한다 — 아직 존재하지 않는 데이터를 빈 배열로 흉내 내지 않는다.
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
		Instant updatedAt
) {
}
