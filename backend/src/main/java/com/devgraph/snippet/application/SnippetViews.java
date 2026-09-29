package com.devgraph.snippet.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.tag.application.TagView;

/** 설계서 §14.4/§14.7 Snippet 응답 모양. 공통 Node 필드 + `snippet` 메타 + 현재 버전. */
public final class SnippetViews {

	private SnippetViews() {
	}

	public record SnippetMeta(String language, String framework, int currentVersionNo, long useCount,
			Instant lastUsedAt, String secretScanStatus) {
	}

	public record VersionContent(int versionNo, String code, String changeSummary, Instant createdAt) {
	}

	public record SnippetDetail(UUID id, String type, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt, SnippetMeta snippet,
			VersionContent currentVersion) {
	}

	/** 목록용. 코드 원문은 싣지 않는다(목록 payload를 키우지 않기 위해). */
	public record SnippetSummary(UUID id, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt, String language,
			String framework, int currentVersionNo, long useCount, Instant lastUsedAt) {
	}

	public record VersionSummary(int versionNo, String changeSummary, Instant createdAt, int codeLength) {
	}
}
