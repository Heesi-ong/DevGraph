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
import com.devgraph.problem.application.ProblemViews.SolutionDetail;
import com.devgraph.problem.application.ProblemViews.SolutionSummary;
import com.devgraph.problem.application.SolutionService;
import com.devgraph.problem.application.SolutionService.CreateCommand;
import com.devgraph.problem.application.SolutionService.UpdateCommand;

/** 설계서 §14.6 Solution API. `errorNodeId`를 주면 Solution과 `Error --SOLVED_BY--> Solution`을 한 트랜잭션으로 만든다. */
@RestController
@RequestMapping("/api/v1/solutions")
public class SolutionController {

	public record CreateSolutionRequest(String title, String summary, String approachMd, String stepsMd,
			String verificationMd, String tradeoffsMd, Instant resolvedAt, UUID errorNodeId, List<UUID> tagIds) {
	}

	public record UpdateSolutionRequest(@NotNull Long version, String title, String summary, String approachMd,
			String stepsMd, String verificationMd, String tradeoffsMd, Instant resolvedAt, List<UUID> tagIds) {
	}

	private final SolutionService service;

	public SolutionController(SolutionService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SolutionDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateSolutionRequest r) {
		return service.create(user.userId(), new CreateCommand(r.title(), r.summary(), r.approachMd(), r.stepsMd(),
				r.verificationMd(), r.tradeoffsMd(), r.resolvedAt(), r.errorNodeId(), r.tagIds()));
	}

	@GetMapping
	public PageResponse<SolutionSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) UUID errorId, @RequestParam(required = false) UUID projectId,
			@RequestParam(required = false) UUID tagId, @RequestParam(required = false) NodeStatus status,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return service.list(user.userId(), errorId, projectId, tagId, status, cursor, size);
	}

	@GetMapping("/{id}")
	public SolutionDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return service.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public SolutionDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateSolutionRequest r) {
		return service.update(user.userId(), id, new UpdateCommand(r.version(), r.title(), r.summary(), r.approachMd(),
				r.stepsMd(), r.verificationMd(), r.tradeoffsMd(), r.resolvedAt(), r.tagIds()));
	}
}
