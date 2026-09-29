package com.devgraph.tag.presentation;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

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
import com.devgraph.tag.application.TagService;
import com.devgraph.tag.application.TagView;
import com.devgraph.workspace.application.WorkspaceQueryService;

/** 설계서 §14.3 Tag API. */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

	public record CreateTagRequest(@NotBlank String name, String color) {
	}

	public record UpdateTagRequest(String name, String color) {
	}

	private final TagService tagService;
	private final WorkspaceQueryService workspaceQueryService;

	public TagController(TagService tagService, WorkspaceQueryService workspaceQueryService) {
		this.tagService = tagService;
		this.workspaceQueryService = workspaceQueryService;
	}

	@GetMapping
	public PageResponse<TagView> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return tagService.list(workspaceQueryService.requireWorkspaceId(user.userId()), q, cursor, size);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TagView create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateTagRequest request) {
		return tagService.create(workspaceQueryService.requireWorkspaceId(user.userId()), request.name(),
				request.color());
	}

	@PatchMapping("/{id}")
	public TagView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@RequestBody UpdateTagRequest request) {
		return tagService.update(workspaceQueryService.requireWorkspaceId(user.userId()), id, request.name(),
				request.color());
	}
}
