package com.devgraph.knowledge.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.web.IpPrefixExtractor;
import com.devgraph.common.web.PageResponse;
import com.devgraph.knowledge.application.FavoriteService;
import com.devgraph.knowledge.application.NodeCommandService;
import com.devgraph.knowledge.application.NodeDetail;
import com.devgraph.knowledge.application.NodePurgeService;
import com.devgraph.knowledge.application.NodeQueryService;
import com.devgraph.knowledge.application.NodeSummary;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;

/** 설계서 §14.3 Knowledge API. 영구 삭제(`permanent-delete`)는 재인증 메커니즘과 함께 구현한다. */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {

	public record CreateNodeRequest(@NotNull NodeType type, String title, String summary, String bodyMd,
			List<UUID> tagIds) {
	}

	public record UpdateNodeRequest(@NotNull Long version, String title, String summary, String bodyMd,
			List<UUID> tagIds) {
	}

	public record VersionRequest(@NotNull Long version) {
	}

	private final NodeCommandService commandService;
	private final NodeQueryService queryService;
	private final FavoriteService favoriteService;
	private final NodePurgeService purgeService;

	public NodeController(NodeCommandService commandService, NodeQueryService queryService,
			FavoriteService favoriteService, NodePurgeService purgeService) {
		this.commandService = commandService;
		this.queryService = queryService;
		this.favoriteService = favoriteService;
		this.purgeService = purgeService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NodeDetail create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateNodeRequest request) {
		return commandService.create(user.userId(), request.type(), request.title(), request.summary(),
				request.bodyMd(), request.tagIds());
	}

	@GetMapping
	public PageResponse<NodeSummary> list(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(required = false) NodeType type, @RequestParam(required = false) UUID tagId,
			@RequestParam(required = false) NodeStatus status, @RequestParam(required = false) Boolean favorite,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return queryService.list(user.userId(), type, tagId, status, favorite, sort, cursor, size);
	}

	@GetMapping("/{id}")
	public NodeDetail get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return queryService.get(user.userId(), id);
	}

	@PatchMapping("/{id}")
	public NodeDetail update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody UpdateNodeRequest request) {
		return commandService.update(user.userId(), id, request.version(), request.title(), request.summary(),
				request.bodyMd(), request.tagIds());
	}

	@PostMapping("/{id}/archive")
	public NodeSummary archive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody VersionRequest request) {
		return commandService.archive(user.userId(), id, request.version());
	}

	@PostMapping("/{id}/archive/restore")
	public NodeSummary restoreFromArchive(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody VersionRequest request) {
		return commandService.restoreFromArchive(user.userId(), id, request.version());
	}

	@PostMapping("/{id}/trash")
	public NodeSummary trash(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody VersionRequest request) {
		return commandService.trash(user.userId(), id, request.version());
	}

	@PostMapping("/{id}/trash/restore")
	public NodeSummary restoreFromTrash(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody VersionRequest request) {
		return commandService.restoreFromTrash(user.userId(), id, request.version());
	}

	@PutMapping("/{id}/favorite")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void addFavorite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		favoriteService.add(user.userId(), id);
	}

	@DeleteMapping("/{id}/favorite")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeFavorite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		favoriteService.remove(user.userId(), id);
	}

	/**
	 * 영구 삭제(DATA-03). 휴지통의 Node만 가능하고 `X-Reauth-Token`(`NODE_PERMANENT_DELETE`, 대상 = 이 Node)이 필요하다.
	 */
	@org.springframework.web.bind.annotation.PostMapping("/{id}/permanent-delete")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void permanentDelete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@RequestHeader(value = "X-Reauth-Token", required = false) String reauthToken, HttpServletRequest http) {
		purgeService.purgeByUser(user, id, reauthToken, IpPrefixExtractor.from(http));
	}
}
