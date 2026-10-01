package com.devgraph.project.presentation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.web.PageResponse;
import com.devgraph.graph.application.GraphViews.GraphResponse;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.project.application.ProjectService;
import com.devgraph.project.application.ProjectService.CreateCommand;
import com.devgraph.project.application.ProjectService.UpdateCommand;
import com.devgraph.project.application.ProjectViews.ProjectDetail;
import com.devgraph.project.application.ProjectViews.ProjectSummary;
import com.devgraph.project.domain.ProjectStatus;

/** 설계서 §14.6 Project API. archive/trash/favorite은 Node 공통 endpoint를 쓴다. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

	public record CreateProjectRequest(String title, String summary, String description, ProjectStatus projectStatus,
			String repositoryUrl, LocalDate startedOn, LocalDate endedOn, List<UUID> tagIds) {
	}

	public record UpdateProjectRequest(@NotNull Long version, String title, String summary, String description,
			ProjectStatus projectStatus, String repositoryUrl, LocalDate startedOn, LocalDate endedOn,
			Boolean clearStartedOn, Boolean clearEndedOn, List<UUID> tagIds) {
	}

	private final ProjectService service;

	public ProjectController(ProjectService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateProjectRequest r) {
		return service.create(user.userId(), new CreateCommand(r.title(), r.summary(), r.description(),
				r.projectStatus(), r.repositoryUrl(), r.startedOn(), r.endedOn(), r.tagIds()));
	}

	@GetMapping
	public PageResponse<ProjectSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) List<ProjectStatus> projectStatus,
			@RequestParam(required = false) UUID tagId, @RequestParam(required = false) NodeStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return service.list(user.userId(), projectStatus, tagId, status, cursor, size);
	}

	@GetMapping("/{id}")
	public ProjectDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return service.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public ProjectDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateProjectRequest r) {
		return service.update(user.userId(), id, new UpdateCommand(r.version(), r.title(), r.summary(),
				r.description(), r.projectStatus(), r.repositoryUrl(), r.startedOn(), r.endedOn(),
				Boolean.TRUE.equals(r.clearStartedOn()), Boolean.TRUE.equals(r.clearEndedOn()), r.tagIds()));
	}

	@GetMapping("/{id}/graph")
	public GraphResponse graph(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@RequestParam(required = false) Integer depth, @RequestParam(required = false) List<NodeType> nodeTypes,
			@RequestParam(required = false) List<String> relationTypes,
			@RequestParam(defaultValue = "false") boolean archived) {
		return service.graph(user.userId(), id, depth, nodeTypes, relationTypes, archived);
	}
}
