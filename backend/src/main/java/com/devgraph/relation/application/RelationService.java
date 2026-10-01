package com.devgraph.relation.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.ActivityEvent;
import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.relation.application.RelationViews.RelationView;
import com.devgraph.relation.infrastructure.RelationJpaEntity;
import com.devgraph.relation.infrastructure.RelationQueryRepository;
import com.devgraph.relation.infrastructure.RelationQueryRepository.NodeRef;
import com.devgraph.relation.infrastructure.RelationRepository;
import com.devgraph.relation.infrastructure.RelationStore;
import com.devgraph.relation.infrastructure.RelationTypeJpaEntity;
import com.devgraph.relation.infrastructure.RelationTypeRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.4 REL-01/02 관계 생성·수정·삭제와 §13.1.1 허용 조합 검증.
 * 검증 순서: self-loop → 관계 타입 → 두 Node 존재(같은 Workspace) → 휴지통 여부 → 허용 타입 조합 → 중복.
 * 동시 요청 경합의 최종 방어는 DB UNIQUE/CHECK이고 변환은 {@link RelationStore}가 맡는다.
 */
@Service
public class RelationService {

	private static final int MAX_NOTE = 500;

	private final RelationRepository relationRepository;
	private final RelationTypeRepository typeRepository;
	private final RelationQueryRepository queryRepository;
	private final RelationStore store;
	private final WorkspaceQueryService workspaceQueryService;
	private final ApplicationEventPublisher events;

	public RelationService(RelationRepository relationRepository, RelationTypeRepository typeRepository,
			RelationQueryRepository queryRepository, RelationStore store,
			WorkspaceQueryService workspaceQueryService, ApplicationEventPublisher events) {
		this.relationRepository = relationRepository;
		this.typeRepository = typeRepository;
		this.queryRepository = queryRepository;
		this.store = store;
		this.workspaceQueryService = workspaceQueryService;
		this.events = events;
	}

	@Transactional
	public RelationView create(UUID userId, UUID sourceId, UUID targetId, UUID relationTypeId, String note) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		if (sourceId == null || targetId == null) {
			throw validation("sourceNodeId", "REQUIRED");
		}
		if (sourceId.equals(targetId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_RELATION_NOT_ALLOWED", "자기 자신과는 연결할 수 없습니다.");
		}
		RelationTypeJpaEntity type = requireType(workspaceId, relationTypeId);
		Map<UUID, NodeRef> nodes = requireNodes(workspaceId, sourceId, targetId);
		requireNotTrashed(nodes.values());
		requireAllowed(type, nodes.get(sourceId), nodes.get(targetId));
		String cleanNote = cleanNote(note);

		UUID[] ends = orient(type, sourceId, targetId);
		relationRepository
				.findByWorkspaceIdAndSourceNodeIdAndRelationTypeIdAndTargetNodeId(workspaceId, ends[0], type.getId(), ends[1])
				.ifPresent(existing -> {
					throw duplicate(existing.getId());
				});
		RelationJpaEntity saved = store.insert(
				new RelationJpaEntity(workspaceId, ends[0], ends[1], type.getId(), cleanNote, userId));
		publish(workspaceId, userId, "RELATION_CREATED", saved.getId());
		return view(saved, type);
	}

	/**
	 * 시스템 관계 타입을 key로 지정해 만든다(다른 모듈이 같은 트랜잭션 안에서 체인 edge를 함께 만들 때,
	 * 예: Solution 생성과 `Error --SOLVED_BY--> Solution`). 실패하면 호출한 쪽 트랜잭션 전체가 되돌아간다.
	 */
	@Transactional
	public RelationView createByTypeKey(UUID userId, UUID sourceId, UUID targetId, String typeKey, String note) {
		UUID typeId = typeRepository.findSystemByKey(typeKey).orElseThrow(
				() -> new IllegalStateException("system relation type missing: " + typeKey)).getId();
		return create(userId, sourceId, targetId, typeId, note);
	}

	/** 타입/메모 변경. 요청에서 null인 필드는 변경 없음(빈 메모 문자열이 "지움"). */
	@Transactional
	public RelationView update(UUID userId, UUID relationId, UUID relationTypeId, String note) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		RelationJpaEntity relation = relationRepository.findByIdAndWorkspaceId(relationId, workspaceId)
				.orElseThrow(RelationService::notFound);
		Map<UUID, NodeRef> nodes = requireNodes(workspaceId, relation.getSourceNodeId(), relation.getTargetNodeId());
		requireNotTrashed(nodes.values());

		RelationTypeJpaEntity type = relationTypeId == null
				? typeRepository.findById(relation.getRelationTypeId()).orElseThrow()
				: requireType(workspaceId, relationTypeId);
		if (!type.getId().equals(relation.getRelationTypeId())) {
			requireAllowed(type, nodes.get(relation.getSourceNodeId()), nodes.get(relation.getTargetNodeId()));
		}
		String newNote = note == null ? relation.getNote() : cleanNote(note);
		UUID[] ends = orient(type, relation.getSourceNodeId(), relation.getTargetNodeId());
		boolean changed = !type.getId().equals(relation.getRelationTypeId())
				|| !ends[0].equals(relation.getSourceNodeId())
				|| !Objects.equals(newNote, relation.getNote());
		if (!changed) {
			return view(relation, type);
		}
		relation.change(ends[0], ends[1], type.getId(), newNote);
		store.flush(relation);
		publish(workspaceId, userId, "RELATION_UPDATED", relation.getId());
		return view(relation, type);
	}

	@Transactional
	public void delete(UUID userId, UUID relationId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		RelationJpaEntity relation = relationRepository.findByIdAndWorkspaceId(relationId, workspaceId)
				.orElseThrow(RelationService::notFound);
		relationRepository.delete(relation);
		publish(workspaceId, userId, "RELATION_DELETED", relationId);
	}

	// ---- helpers ---------------------------------------------------------------------------

	/**
	 * §13.1.2: 대칭 타입은 두 id를 소문자 hyphenated UUID 문자열의 사전순으로 정렬한 canonical pair 한 행만 저장한다.
	 * 방향성 타입은 입력 그대로다.
	 */
	static UUID[] orient(RelationTypeJpaEntity type, UUID sourceId, UUID targetId) {
		if (type.isSymmetric() && sourceId.toString().compareTo(targetId.toString()) > 0) {
			return new UUID[] { targetId, sourceId };
		}
		return new UUID[] { sourceId, targetId };
	}

	private RelationTypeJpaEntity requireType(UUID workspaceId, UUID relationTypeId) {
		if (relationTypeId == null) {
			throw validation("relationTypeId", "REQUIRED");
		}
		return typeRepository.findAvailableById(relationTypeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RELATION_TYPE",
						"사용할 수 없는 관계 타입입니다.", List.of(new ApiError.FieldError("relationTypeId", "INVALID"))));
	}

	/** 다른 Workspace의 id는 "없음"과 구분되지 않는다(§17.1). 요청 본문 안의 참조이므로 400이다. */
	private Map<UUID, NodeRef> requireNodes(UUID workspaceId, UUID sourceId, UUID targetId) {
		Map<UUID, NodeRef> nodes = queryRepository.findNodes(workspaceId, Set.of(sourceId, targetId)).stream()
				.collect(Collectors.toMap(NodeRef::id, Function.identity()));
		if (nodes.size() != 2) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RELATION_NODE", "연결할 수 없는 항목입니다.");
		}
		return nodes;
	}

	private static void requireNotTrashed(Iterable<NodeRef> nodes) {
		for (NodeRef node : nodes) {
			if (node.status() == NodeStatus.TRASHED) {
				throw new ApiException(HttpStatus.CONFLICT, "INVALID_NODE_STATE", "휴지통에 있는 항목은 연결할 수 없습니다.");
			}
		}
	}

	private static void requireAllowed(RelationTypeJpaEntity type, NodeRef source, NodeRef target) {
		Set<String> allowedSources = new HashSet<>(type.getAllowedSourceTypes());
		Set<String> allowedTargets = new HashSet<>(type.getAllowedTargetTypes());
		if (!allowedSources.contains(source.type().name()) || !allowedTargets.contains(target.type().name())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "TYPE_NOT_ALLOWED",
					"이 관계는 " + source.type() + " → " + target.type() + " 조합을 허용하지 않습니다.",
					List.of(new ApiError.FieldError("relationTypeId", "TYPE_NOT_ALLOWED")));
		}
	}

	private static String cleanNote(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		String trimmed = note.trim();
		if (trimmed.length() > MAX_NOTE || trimmed.indexOf('\u0000') >= 0) {
			throw validation("note", "MAX_" + MAX_NOTE);
		}
		return trimmed;
	}

	private static RelationView view(RelationJpaEntity r, RelationTypeJpaEntity type) {
		return new RelationView(r.getId(), r.getSourceNodeId(), r.getTargetNodeId(), type.getId(), type.getKey(),
				r.getNote(), r.getCreatedAt(), r.getUpdatedAt());
	}

	private void publish(UUID workspaceId, UUID userId, String action, UUID relationId) {
		events.publishEvent(new ActivityEvent(workspaceId, userId, action, "RELATION", relationId));
	}

	/** §13.1.2: 이미 있는 관계(반대 방향으로 요청한 대칭 관계 포함)는 기존 관계 id를 알려 준다. */
	private static ApiException duplicate(UUID existingId) {
		return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RELATION", "이미 같은 관계가 있습니다.",
				List.of(new ApiError.FieldError("relationId", existingId.toString())));
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "관계를 찾을 수 없습니다.");
	}

	private static ApiException validation(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
				List.of(new ApiError.FieldError(field, reason)));
	}
}
