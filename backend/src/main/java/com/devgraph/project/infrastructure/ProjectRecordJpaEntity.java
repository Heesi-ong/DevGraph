package com.devgraph.project.infrastructure;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.devgraph.common.persistence.SubtypeRecord;
import com.devgraph.project.domain.ProjectStatus;

/** 설계서 §12.3 `projects`. 설명은 Node의 `body_md`를 쓴다(별도 컬럼 없음). */
@Entity
@Table(name = "projects")
public class ProjectRecordJpaEntity extends SubtypeRecord {

	@Column(name = "project_status", nullable = false)
	private String projectStatus;

	@Column(name = "repository_url")
	private String repositoryUrl;

	@Column(name = "started_on")
	private LocalDate startedOn;

	@Column(name = "ended_on")
	private LocalDate endedOn;

	protected ProjectRecordJpaEntity() {
	}

	public ProjectRecordJpaEntity(UUID nodeId, UUID workspaceId, ProjectStatus status, String repositoryUrl,
			LocalDate startedOn, LocalDate endedOn) {
		super(nodeId, workspaceId);
		edit(status, repositoryUrl, startedOn, endedOn);
	}

	public ProjectStatus getProjectStatus() {
		return ProjectStatus.valueOf(projectStatus);
	}

	public String getRepositoryUrl() {
		return repositoryUrl;
	}

	public LocalDate getStartedOn() {
		return startedOn;
	}

	public LocalDate getEndedOn() {
		return endedOn;
	}

	public final void edit(ProjectStatus status, String repositoryUrl, LocalDate startedOn, LocalDate endedOn) {
		this.projectStatus = status.name();
		this.repositoryUrl = repositoryUrl;
		this.startedOn = startedOn;
		this.endedOn = endedOn;
	}
}
