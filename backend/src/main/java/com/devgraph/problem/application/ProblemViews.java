package com.devgraph.problem.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.devgraph.relation.application.RelationViews.NodeRelations;
import com.devgraph.tag.application.TagView;

/** 설계서 §14.6/§14.7 Error·Solution 응답 모양. 공통 Node 필드 + subtype 객체 + 관계. */
public final class ProblemViews {

	private ProblemViews() {
	}

	public record ErrorData(String errorMessage, String environment, String reproductionStepsMd,
			String causeHypothesisMd, String resolutionStatus, Instant occurredAt, Instant resolvedAt) {
	}

	/** `warnings`: 저장은 됐지만 사용자가 알아야 할 것(예: `NO_SOLUTION_LINKED`, §9.7). */
	public record ErrorDetail(UUID id, String type, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt, ErrorData error,
			NodeRelations relations, List<String> warnings) {
	}

	/** 목록용. `solutionCount`/`projectCount`는 체인 미리보기(Problems)다. */
	public record ErrorSummary(UUID id, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant updatedAt, String messagePreview, String resolutionStatus,
			Instant occurredAt, Instant resolvedAt, long solutionCount, long projectCount) {
	}

	public record SolutionData(String approachMd, String stepsMd, String verificationMd, String tradeoffsMd,
			Instant resolvedAt) {
	}

	public record SolutionDetail(UUID id, String type, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt, SolutionData solution,
			NodeRelations relations) {
	}

	/** `errorCount`: 이 Solution이 해결하는 Error 수(incoming SOLVED_BY), `snippetCount`: IMPLEMENTED_WITH. */
	public record SolutionSummary(UUID id, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant updatedAt, String approachPreview, Instant resolvedAt,
			long errorCount, long snippetCount, long projectCount) {
	}
}
