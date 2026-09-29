package com.devgraph.snippet.presentation;

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
import com.devgraph.snippet.application.SnippetService;
import com.devgraph.snippet.application.SnippetService.CreateCommand;
import com.devgraph.snippet.application.SnippetService.UpdateCommand;
import com.devgraph.snippet.application.SnippetViews.SnippetDetail;
import com.devgraph.snippet.application.SnippetViews.SnippetSummary;
import com.devgraph.snippet.application.SnippetViews.VersionContent;
import com.devgraph.snippet.application.SnippetViews.VersionSummary;

/**
 * 설계서 §14.4 Snippet API. 상태 전이와 즐겨찾기는 Node 공통 endpoint(`/nodes/{id}/archive` 등)를 그대로 쓴다.
 * `relations[]` 요청 필드는 Relation을 구현하는 Phase 4에서 추가한다.
 * diff endpoint(SNP-07, SHOULD)는 이번 Phase 범위에서 제외했다.
 */
@RestController
@RequestMapping("/api/v1/snippets")
public class SnippetController {

	public record CreateSnippetRequest(String title, String summary, String language, String framework, String code,
			String changeSummary, List<UUID> tagIds, String secretConfirmation) {
	}

	public record UpdateSnippetRequest(@NotNull Long version, String title, String summary, String language,
			String framework, String code, String changeSummary, List<UUID> tagIds, String secretConfirmation) {
	}

	public record UsageRequest(String action) {
	}

	private final SnippetService service;

	public SnippetController(SnippetService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SnippetDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateSnippetRequest request) {
		return service.create(user.userId(), new CreateCommand(request.title(), request.summary(), request.language(),
				request.framework(), request.code(), request.changeSummary(), request.tagIds(),
				request.secretConfirmation()));
	}

	@GetMapping
	public PageResponse<SnippetSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) String language, @RequestParam(required = false) String framework,
			@RequestParam(required = false) UUID tagId, @RequestParam(required = false) NodeStatus status,
			@RequestParam(required = false) Boolean favorite, @RequestParam(required = false) String sort,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return service.list(user.userId(), language, framework, tagId, status, favorite, sort, cursor, size);
	}

	@GetMapping("/{id}")
	public SnippetDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return service.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public SnippetDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateSnippetRequest request) {
		return service.update(user.userId(), id, new UpdateCommand(request.version(), request.title(),
				request.summary(), request.language(), request.framework(), request.code(), request.changeSummary(),
				request.tagIds(), request.secretConfirmation()));
	}

	@GetMapping("/{id}/versions")
	public PageResponse<VersionSummary> versions(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID id, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return service.listVersions(user.userId(), id, cursor, size);
	}

	@GetMapping("/{id}/versions/{versionNo}")
	public VersionContent version(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@PathVariable int versionNo) {
		return service.getVersion(user.userId(), id, versionNo);
	}

	@PostMapping("/{id}/usage")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void usage(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@RequestBody UsageRequest request) {
		service.recordUse(user.userId(), id, request.action());
	}
}
