package com.devgraph.project.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.devgraph.relation.application.RelationViews.NodeRelations;
import com.devgraph.tag.application.TagView;

/** 설계서 §14.6 Project 응답. 설명(`description`)은 Node의 본문(`bodyMd`)이다. */
public final class ProjectViews {

	private ProjectViews() {
	}

	public record ProjectData(String projectStatus, String repositoryUrl, LocalDate startedOn, LocalDate endedOn) {
	}

	public record ProjectDetail(UUID id, String type, String title, String summary, String description, String status,
			long version, List<TagView> tags, boolean favorite, Instant createdAt, Instant updatedAt,
			ProjectData project, NodeRelations relations) {
	}

	/** `problemCount`: 이 프로젝트에서 발생한 Error(incoming OCCURRED_IN), `knowledgeCount`: USED_IN(지식·Snippet·Resource). */
	public record ProjectSummary(UUID id, String title, String summary, String status, long version,
			List<TagView> tags, boolean favorite, Instant updatedAt, String projectStatus, String repositoryUrl,
			LocalDate startedOn, LocalDate endedOn, long problemCount, long knowledgeCount, long solutionCount) {
	}
}
