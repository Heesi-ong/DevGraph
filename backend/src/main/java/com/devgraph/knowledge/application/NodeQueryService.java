package com.devgraph.knowledge.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.CursorCodec;
import com.devgraph.common.web.PageResponse;
import com.devgraph.common.web.PageSize;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeJpaEntity;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeQueryRepository;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/** 설계서 §9.2 KNOW-02 목록·상세 조회, KNOW-08 최근 조회 기록. */
@Service
public class NodeQueryService {

	private final KnowledgeNodeRepository nodeRepository;
	private final KnowledgeNodeQueryRepository queryRepository;
	private final NodeAssembler assembler;
	private final NodeViewRecorder viewRecorder;
	private final WorkspaceQueryService workspaceQueryService;

	public NodeQueryService(KnowledgeNodeRepository nodeRepository, KnowledgeNodeQueryRepository queryRepository,
			NodeAssembler assembler, NodeViewRecorder viewRecorder, WorkspaceQueryService workspaceQueryService) {
		this.nodeRepository = nodeRepository;
		this.queryRepository = queryRepository;
		this.assembler = assembler;
		this.viewRecorder = viewRecorder;
		this.workspaceQueryService = workspaceQueryService;
	}

	@Transactional(readOnly = true)
	public PageResponse<NodeSummary> list(UUID userId, NodeType type, UUID tagId, NodeStatus status,
			Boolean favorite, String sort, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		// 현재 지원하는 정렬은 수정일 최신순 하나다. 모르는 값을 조용히 무시하지 않고 알린다.
		if (sort != null && !sort.equals("updatedAt")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("sort", "UNSUPPORTED_SORT")));
		}

		Instant afterUpdatedAt = null;
		UUID afterId = null;
		if (cursor != null && !cursor.isBlank()) {
			List<String> parts = CursorCodec.decode(cursor, 2);
			try {
				afterUpdatedAt = Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS);
				afterId = UUID.fromString(parts.get(1));
			} catch (RuntimeException e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
			}
		}

		List<KnowledgeNodeJpaEntity> rows = queryRepository.search(workspaceId, userId,
				status == null ? NodeStatus.ACTIVE : status, type, tagId, Boolean.TRUE.equals(favorite),
				afterUpdatedAt, afterId, size + 1);
		boolean hasMore = rows.size() > size;
		List<KnowledgeNodeJpaEntity> page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasMore) {
			KnowledgeNodeJpaEntity last = page.get(page.size() - 1);
			long micros = ChronoUnit.MICROS.between(Instant.EPOCH, last.getUpdatedAt());
			nextCursor = CursorCodec.encode(Long.toString(micros), last.getId().toString());
		}
		return new PageResponse<>(assembler.toSummaries(workspaceId, userId, page), nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public NodeDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		KnowledgeNodeJpaEntity node = nodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
		NodeDetail detail = assembler.toDetail(workspaceId, userId, node);
		viewRecorder.record(workspaceId, userId, nodeId);
		return detail;
	}
}
