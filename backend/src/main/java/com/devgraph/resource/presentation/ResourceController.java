package com.devgraph.resource.presentation;

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
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.resource.application.ResourceService;
import com.devgraph.resource.application.ResourceService.CreateCommand;
import com.devgraph.resource.application.ResourceService.UpdateCommand;
import com.devgraph.resource.application.ResourceViews.ResourceDetail;
import com.devgraph.resource.application.ResourceViews.ResourceSummary;

/** Resource API. archive/trash/favorite은 Node 공통 endpoint를 쓴다. 서버는 URL 내용을 가져오지 않는다(§17.4). */
@RestController
@RequestMapping("/api/v1/resources")
public class ResourceController {

	public record CreateResourceRequest(String title, String summary, String url, String kind, String siteName,
			List<UUID> tagIds) {
	}

	public record UpdateResourceRequest(@NotNull Long version, String title, String summary, String url, String kind,
			String siteName, List<UUID> tagIds) {
	}

	private final ResourceService service;

	public ResourceController(ResourceService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ResourceDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateResourceRequest r) {
		return service.create(user.userId(),
				new CreateCommand(r.title(), r.summary(), r.url(), r.kind(), r.siteName(), r.tagIds()));
	}

	@GetMapping
	public PageResponse<ResourceSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) List<String> kind, @RequestParam(required = false) UUID tagId,
			@RequestParam(required = false) NodeStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return service.list(user.userId(), kind, tagId, status, cursor, size);
	}

	@GetMapping("/{id}")
	public ResourceDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return service.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public ResourceDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateResourceRequest r) {
		return service.update(user.userId(), id,
				new UpdateCommand(r.version(), r.title(), r.summary(), r.url(), r.kind(), r.siteName(), r.tagIds()));
	}
}
