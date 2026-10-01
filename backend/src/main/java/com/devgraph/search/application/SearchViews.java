package com.devgraph.search.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 설계서 §14.6/§14.7 검색 응답. highlight는 HTML이 아니라 `{text, matched}` 구간 배열이다. */
public final class SearchViews {

	private SearchViews() {
	}

	public record Segment(String text, boolean matched) {
	}

	/** `versionNo`는 `scope=snippetHistory` 결과에서만 채워진다(일치한 과거 버전 번호, §9.5). 기본 검색에서는 null. */
	public record SearchHit(UUID id, String type, String title, String status, double score,
			List<String> matchedFields, Map<String, List<Segment>> highlight, String language, String framework,
			boolean favorite, Instant updatedAt, Integer versionNo) {
	}

	public record RecentItem(UUID id, String type, String title, Instant updatedAt) {
	}

	/**
	 * 0건일 때만(첫 페이지) 준다. `unfilteredCount`는 필터를 풀면 몇 건인지(필터가 없었으면 null),
	 * `similar`는 완화된 trigram 임계값으로 찾은 "유사 결과", `recent`는 그래도 없을 때의 최근 항목이다.
	 */
	public record Fallback(Integer unfilteredCount, List<SearchHit> similar, List<RecentItem> recent) {
	}

	public record SearchPage(List<SearchHit> items, String cursor, boolean hasMore, Fallback fallback) {
	}
}
