package com.devgraph.problem.presentation;

import java.time.Instant;
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
import com.devgraph.problem.application.ErrorService;
import com.devgraph.problem.application.ErrorService.CreateCommand;
import com.devgraph.problem.application.ErrorService.UpdateCommand;
import com.devgraph.problem.application.ProblemViews.ErrorDetail;
import com.devgraph.problem.application.ProblemViews.ErrorSummary;
import com.devgraph.problem.domain.ResolutionStatus;

/**
 * 설계서 §14.6 Error API. 상태 전이(archive/trash)와 즐겨찾기는 Node 공통 endpoint(`/nodes/{id}/...`)를 쓴다.
 * `resolution`은 Error의 해결 상태, `status`는 Node의 보관 상태(ACTIVE/ARCHIVED/TRASHED)다.
 */
@RestController
@RequestMapping("/api/v1/errors")
public class ErrorController {

	public record CreateErrorRequest(String title, String summary, String errorMessage, String environment,
			String reproductionStepsMd, String causeHypothesisMd, Instant occurredAt, List<UUID> tagIds) {
	}

	public record UpdateErrorRequest(@NotNull Long version, String title, String summary, String errorMessage,
			String environment, String reproductionStepsMd, String causeHypothesisMd, Instant occurredAt,
			List<UUID> tagIds) {
	}

	public record StatusRequest(@NotNull Long version, @NotNull ResolutionStatus status, Instant resolvedAt) {
	}

	private final ErrorService service;

	public ErrorController(ErrorService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ErrorDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateErrorRequest r) {
		return service.create(user.userId(), new CreateCommand(r.title(), r.summary(), r.errorMessage(),
				r.environment(), r.reproductionStepsMd(), r.causeHypothesisMd(), r.occurredAt(), r.tagIds()));
	}

	@GetMapping
	public PageResponse<ErrorSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) List<ResolutionStatus> resolution,
			@RequestParam(required = false) UUID projectId, @RequestParam(required = false) UUID tagId,
			@RequestParam(required = false) NodeStatus status, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return service.list(user.userId(), resolution, projectId, tagId, status, cursor, size);
	}

	@GetMapping("/{id}")
	public ErrorDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return service.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public ErrorDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateErrorRequest r) {
		return service.update(user.userId(), id, new UpdateCommand(r.version(), r.title(), r.summary(),
				r.errorMessage(), r.environment(), r.reproductionStepsMd(), r.causeHypothesisMd(), r.occurredAt(),
				r.tagIds()));
	}

	@PatchMapping("/{id}/status")
	public ErrorDetail changeStatus(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody StatusRequest r) {
		return service.changeStatus(user.userId(), id, r.version(), r.status(), r.resolvedAt());
	}
}
