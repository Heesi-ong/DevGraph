package com.devgraph.knowledge.application;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.ActivityEvent;
import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeJpaEntity;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeRepository;
import com.devgraph.knowledge.infrastructure.NodeTagJpaEntity;
import com.devgraph.knowledge.infrastructure.NodeTagRepository;
import com.devgraph.tag.application.TagService;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.2 KNOW-01/03/04/05: 생성, 부분 수정(optimistic lock), archive, trash 상태 전이.
 * 영구 삭제(재인증 필요, §17.2.2)와 자동 purge는 재인증 메커니즘과 함께 구현한다.
 */
@Service
public class NodeCommandService {

	// 이번 릴리스에서 만들 수 있는 타입. 나머지 타입은 subtype 테이블이 생기는 Phase에서 연다(§19).
	private static final Set<NodeType> CREATABLE_TYPES = EnumSet.of(NodeType.CONCEPT, NodeType.NOTE);

	private static final int MAX_TITLE = 200;
	private static final int MAX_SUMMARY = 1000;
	private static final int MAX_BODY_BYTES = 1_000_000; // §21.6 Node body 1 MB

	private final KnowledgeNodeRepository nodeRepository;
	private final NodeTagRepository nodeTagRepository;
	private final TagService tagService;
	private final NodeAssembler assembler;
	private final WorkspaceQueryService workspaceQueryService;
	private final ApplicationEventPublisher events;

	public NodeCommandService(KnowledgeNodeRepository nodeRepository, NodeTagRepository nodeTagRepository,
			TagService tagService, NodeAssembler assembler, WorkspaceQueryService workspaceQueryService,
			ApplicationEventPublisher events) {
		this.nodeRepository = nodeRepository;
		this.nodeTagRepository = nodeTagRepository;
		this.tagService = tagService;
		this.assembler = assembler;
		this.workspaceQueryService = workspaceQueryService;
		this.events = events;
	}

	@Transactional
	public NodeDetail create(UUID userId, NodeType type, String title, String summary, String bodyMd,
			List<UUID> tagIds) {
		if (type == null || !CREATABLE_TYPES.contains(type)) {
			throw validation("type", "UNSUPPORTED_TYPE");
		}
		return createNode(userId, type, title, summary, bodyMd, tagIds, "NODE_CREATED");
	}

	/**
	 * subtype(Snippet 등) 생성의 공통 부분: node 행과 태그만 만든다. subtype 행은 호출한 모듈이 같은
	 * 트랜잭션에서 추가한다. 일반 `create`가 여는 타입과 별개라 이 경로로는 CONCEPT/NOTE를 만들 수 없다.
	 */
	@Transactional
	public NodeDetail createSubtypeNode(UUID userId, NodeType type, String title, String summary,
			List<UUID> tagIds, String activityAction) {
		return createSubtypeNode(userId, type, title, summary, null, tagIds, activityAction);
	}

	/** 본문(`bodyMd`)을 쓰는 subtype(예: Project의 설명)용. */
	@Transactional
	public NodeDetail createSubtypeNode(UUID userId, NodeType type, String title, String summary, String bodyMd,
			List<UUID> tagIds, String activityAction) {
		if (type == null || CREATABLE_TYPES.contains(type)) {
			throw validation("type", "UNSUPPORTED_TYPE");
		}
		return createNode(userId, type, title, summary, bodyMd, tagIds, activityAction);
	}

	private NodeDetail createNode(UUID userId, NodeType type, String title, String summary, String bodyMd,
			List<UUID> tagIds, String activityAction) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		String cleanTitle = requireTitle(title);
		String cleanSummary = optionalText("summary", summary, MAX_SUMMARY);
		String cleanBody = optionalBody(bodyMd);
		Set<UUID> tags = tagIds == null ? Set.of() : new HashSet<>(tagIds);
		if (!tags.isEmpty()) {
			tagService.requireAllExist(workspaceId, tags);
		}

		KnowledgeNodeJpaEntity node = nodeRepository.save(new KnowledgeNodeJpaEntity(UUID.randomUUID(), workspaceId,
				userId, type, cleanTitle, cleanSummary, cleanBody));
		nodeTagRepository.saveAll(tags.stream().map(t -> new NodeTagJpaEntity(workspaceId, node.getId(), t)).toList());
		publish(workspaceId, userId, activityAction, node.getId());
		return assembler.toDetail(workspaceId, userId, node);
	}

	/**
	 * 부분 수정. 요청에서 null인 필드는 "변경 없음"이다(빈 문자열이 "지움"). 실제로 바뀐 게 없으면
	 * version/updatedAt을 올리지 않는다 — 불필요한 갱신이 "최근 수정" 순서를 흔들지 않게 한다.
	 */
	@Transactional
	public NodeDetail update(UUID userId, UUID nodeId, Long version, String title, String summary, String bodyMd,
			List<UUID> tagIds) {
		return doUpdate(userId, nodeId, version, title, summary, bodyMd, tagIds, false, false, "NODE_UPDATED");
	}

	/**
	 * subtype 수정의 공통 부분(제목·요약·태그). subtype 고유 필드만 바뀌어도 node의 version/updatedAt이
	 * 올라야 하므로 호출자가 `subtypeChanged`로 알린다.
	 */
	@Transactional
	public NodeDetail updateSubtypeNode(UUID userId, UUID nodeId, Long version, String title, String summary,
			List<UUID> tagIds, boolean subtypeChanged, String activityAction) {
		return updateSubtypeNode(userId, nodeId, version, title, summary, null, tagIds, subtypeChanged, activityAction);
	}

	/** 본문(`bodyMd`)을 쓰는 subtype용. `bodyMd`가 null이면 변경 없음. */
	@Transactional
	public NodeDetail updateSubtypeNode(UUID userId, UUID nodeId, Long version, String title, String summary,
			String bodyMd, List<UUID> tagIds, boolean subtypeChanged, String activityAction) {
		return doUpdate(userId, nodeId, version, title, summary, bodyMd, tagIds, true, subtypeChanged, activityAction);
	}

	private NodeDetail doUpdate(UUID userId, UUID nodeId, Long version, String title, String summary, String bodyMd,
			List<UUID> tagIds, boolean subtype, boolean subtypeChanged, String activityAction) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		KnowledgeNodeJpaEntity node = load(workspaceId, nodeId);
		boolean nodeIsSubtype = !CREATABLE_TYPES.contains(node.getNodeType());
		if (subtype && !nodeIsSubtype) {
			throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
		}
		if (!subtype && nodeIsSubtype) {
			// subtype 고유 데이터(예: Snippet 코드 버전)는 전용 API로만 바꾼다. 여기서 우회하면 이력이 어긋난다.
			throw validation("type", "USE_SUBTYPE_API");
		}
		requireVersion(node, version);
		if (node.getStatus() == NodeStatus.TRASHED) {
			throw invalidState();
		}

		String newTitle = title == null ? node.getTitle() : requireTitle(title);
		String newSummary = summary == null ? node.getSummary() : blankToNull(optionalText("summary", summary, MAX_SUMMARY));
		String newBody = bodyMd == null ? node.getBodyMd() : blankToNull(optionalBody(bodyMd));
		boolean changed = !Objects.equals(newTitle, node.getTitle())
				|| !Objects.equals(newSummary, node.getSummary())
				|| !Objects.equals(newBody, node.getBodyMd());
		if (changed) {
			node.edit(newTitle, newSummary, newBody);
		}
		if (tagIds != null && replaceTags(workspaceId, node, new HashSet<>(tagIds))) {
			node.touch();
			changed = true;
		}
		if (!changed && subtypeChanged) {
			node.touch();
			changed = true;
		}
		if (changed) {
			publish(workspaceId, userId, activityAction, node.getId());
		}
		// @Version은 flush 때 증가한다. 응답에 새 version을 담으려면 조립 전에 flush해야 한다.
		nodeRepository.flush();
		return assembler.toDetail(workspaceId, userId, node);
	}

	@Transactional
	public NodeSummary archive(UUID userId, UUID nodeId, Long version) {
		return transition(userId, nodeId, version, EnumSet.of(NodeStatus.ACTIVE), NodeStatus.ARCHIVED, "NODE_ARCHIVED");
	}

	@Transactional
	public NodeSummary restoreFromArchive(UUID userId, UUID nodeId, Long version) {
		return transition(userId, nodeId, version, EnumSet.of(NodeStatus.ARCHIVED), NodeStatus.ACTIVE,
				"NODE_ARCHIVE_RESTORED");
	}

	@Transactional
	public NodeSummary trash(UUID userId, UUID nodeId, Long version) {
		return transition(userId, nodeId, version, EnumSet.of(NodeStatus.ACTIVE, NodeStatus.ARCHIVED),
				NodeStatus.TRASHED, "NODE_TRASHED");
	}

	// §11.3: TRASHED → ACTIVE 직접 복구는 없다. 항상 ARCHIVED를 거쳐 실수로 영구 삭제 직전까지 가지 않게 한다.
	@Transactional
	public NodeSummary restoreFromTrash(UUID userId, UUID nodeId, Long version) {
		return transition(userId, nodeId, version, EnumSet.of(NodeStatus.TRASHED), NodeStatus.ARCHIVED,
				"NODE_TRASH_RESTORED");
	}

	private NodeSummary transition(UUID userId, UUID nodeId, Long version, Set<NodeStatus> allowedFrom,
			NodeStatus target, String action) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		KnowledgeNodeJpaEntity node = load(workspaceId, nodeId);
		requireVersion(node, version);
		if (!allowedFrom.contains(node.getStatus())) {
			throw invalidState();
		}
		node.changeStatus(target);
		publish(workspaceId, userId, action, node.getId());
		nodeRepository.flush();
		return assembler.toSummaries(workspaceId, userId, List.of(node)).get(0);
	}

	/** 원하는 태그 집합과의 차이만 반영한다. 실제로 바뀌었으면 true. */
	private boolean replaceTags(UUID workspaceId, KnowledgeNodeJpaEntity node, Set<UUID> desired) {
		if (!desired.isEmpty()) {
			tagService.requireAllExist(workspaceId, desired);
		}
		Set<UUID> current = nodeTagRepository.findByNodeIdIn(List.of(node.getId())).stream()
				.map(NodeTagJpaEntity::getTagId)
				.collect(Collectors.toSet());
		Set<UUID> toRemove = new HashSet<>(current);
		toRemove.removeAll(desired);
		Set<UUID> toAdd = new HashSet<>(desired);
		toAdd.removeAll(current);
		if (!toRemove.isEmpty()) {
			nodeTagRepository.deleteByNodeIdAndTagIdIn(node.getId(), toRemove);
		}
		if (!toAdd.isEmpty()) {
			nodeTagRepository.saveAll(toAdd.stream().map(t -> new NodeTagJpaEntity(workspaceId, node.getId(), t)).toList());
		}
		return !toRemove.isEmpty() || !toAdd.isEmpty();
	}

	private KnowledgeNodeJpaEntity load(UUID workspaceId, UUID nodeId) {
		return nodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
	}

	private static void requireVersion(KnowledgeNodeJpaEntity node, Long requested) {
		if (requested == null || !requested.equals(node.getVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
					"다른 위치에서 수정되었습니다. 최신 내용을 확인해 주세요.");
		}
	}

	private static ApiException invalidState() {
		return new ApiException(HttpStatus.CONFLICT, "INVALID_NODE_STATE", "현재 상태에서는 할 수 없는 작업입니다.");
	}

	private void publish(UUID workspaceId, UUID userId, String action, UUID nodeId) {
		events.publishEvent(new ActivityEvent(workspaceId, userId, action, "NODE", nodeId));
	}

	private static String requireTitle(String title) {
		String trimmed = title == null ? "" : title.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE) {
			throw validation("title", "LENGTH_1_" + MAX_TITLE);
		}
		rejectNul("title", trimmed);
		return trimmed;
	}

	private static String optionalText(String field, String value, int max) {
		if (value == null) {
			return null;
		}
		if (value.length() > max) {
			throw validation(field, "MAX_" + max);
		}
		rejectNul(field, value);
		return value;
	}

	private static String optionalBody(String bodyMd) {
		if (bodyMd == null) {
			return null;
		}
		if (bodyMd.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
			// §21.6: 제한 초과는 413과 구체적인 field/limit을 반환하며 입력을 조용히 자르지 않는다.
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "본문이 너무 큽니다.",
					List.of(new ApiError.FieldError("bodyMd", "MAX_1MB")));
		}
		rejectNul("bodyMd", bodyMd);
		return bodyMd;
	}

	// PostgreSQL text는 NUL(0x00)을 저장할 수 없다. 검증 없이 두면 DB 오류가 500으로 새어 나간다.
	private static void rejectNul(String field, String value) {
		if (value.indexOf('\u0000') >= 0) {
			throw validation(field, "INVALID_CHARACTER");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isEmpty() ? null : value;
	}

	private static ApiException validation(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
				List.of(new ApiError.FieldError(field, reason)));
	}
}
