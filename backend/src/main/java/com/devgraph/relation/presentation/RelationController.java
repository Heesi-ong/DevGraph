package com.devgraph.relation.presentation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.devgraph.relation.application.RelationQueryService;
import com.devgraph.relation.application.RelationService;
import com.devgraph.relation.application.RelationViews.RelationTypeView;
import com.devgraph.relation.application.RelationViews.RelationView;
import com.devgraph.relation.application.RelationViews.TargetCandidate;
import com.devgraph.relation.infrastructure.RelationQueryRepository.Side;

/**
 * 설계서 §14.5 Relation API. `POST /relation-types`(사용자 정의)는 Growth 범위라 라우트 자체를 만들지 않는다.
 * `GET /relations/target-candidates`는 Relation Picker를 위한 보조 endpoint다(통합 검색은 Phase 5).
 */
@RestController
@RequestMapping("/api/v1")
public class RelationController {

	public record CreateRelationRequest(@NotNull UUID sourceNodeId, @NotNull UUID targetNodeId,
			@NotNull UUID relationTypeId, String note) {
	}

	public record UpdateRelationRequest(UUID relationTypeId, String note) {
	}

	private final RelationService service;
	private final RelationQueryService queryService;

	public RelationController(RelationService service, RelationQueryService queryService) {
		this.service = service;
		this.queryService = queryService;
	}

	@GetMapping("/relation-types")
	public List<RelationTypeView> types(@AuthenticationPrincipal AuthenticatedUser user) {
		return queryService.listTypes(user.userId());
	}

	@PostMapping("/relations")
	@ResponseStatus(HttpStatus.CREATED)
	public RelationView create(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateRelationRequest request) {
		return service.create(user.userId(), request.sourceNodeId(), request.targetNodeId(),
				request.relationTypeId(), request.note());
	}

	@PatchMapping("/relations/{id}")
	public RelationView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@RequestBody UpdateRelationRequest request) {
		return service.update(user.userId(), id, request.relationTypeId(), request.note());
	}

	@DeleteMapping("/relations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		service.delete(user.userId(), id);
	}

	@GetMapping("/relations/target-candidates")
	public List<TargetCandidate> candidates(@AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam UUID nodeId, @RequestParam UUID relationTypeId,
			@RequestParam(defaultValue = "OUTGOING") Side side, @RequestParam(required = false) String q) {
		return queryService.candidates(user.userId(), nodeId, relationTypeId, side, q);
	}
}
