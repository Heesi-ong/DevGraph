package com.devgraph.problem.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.FieldRules;
import com.devgraph.common.web.KeysetPaging;
import com.devgraph.common.web.PageResponse;
import com.devgraph.common.web.PageSize;
import com.devgraph.knowledge.application.NodeCommandService;
import com.devgraph.knowledge.application.NodeDetail;
import com.devgraph.knowledge.application.NodeQueryService;
import com.devgraph.knowledge.application.NodeSummary;
import com.devgraph.knowledge.application.SubtypeListQueryService;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.problem.application.ProblemViews.SolutionData;
import com.devgraph.problem.application.ProblemViews.SolutionDetail;
import com.devgraph.problem.application.ProblemViews.SolutionSummary;
import com.devgraph.problem.infrastructure.SolutionRecordJpaEntity;
import com.devgraph.problem.infrastructure.SolutionRecordRepository;
import com.devgraph.relation.application.RelationQueryService;
import com.devgraph.relation.application.RelationService;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.7 SOL-01/02. Solution 생성·수정. `errorNodeId`를 주면 Solution과 `Error --SOLVED_BY--> Solution`을
 * **한 트랜잭션**으로 만든다(§15.2 orchestration) — 관계 검증이 실패하면 Solution도 만들어지지 않는다.
 */
@Service
public class SolutionService {

	private static final int MAX_TEXT_BYTES = 100_000;

	private final NodeCommandService nodeCommandService;
	private final NodeQueryService nodeQueryService;
	private final SubtypeListQueryService listQueryService;
	private final SolutionRecordRepository repository;
	private final RelationService relationService;
	private final RelationQueryService relationQueryService;
	private final WorkspaceQueryService workspaceQueryService;

	public SolutionService(NodeCommandService nodeCommandService, NodeQueryService nodeQueryService,
			SubtypeListQueryService listQueryService, SolutionRecordRepository repository,
			RelationService relationService, RelationQueryService relationQueryService,
			WorkspaceQueryService workspaceQueryService) {
		this.nodeCommandService = nodeCommandService;
		this.nodeQueryService = nodeQueryService;
		this.listQueryService = listQueryService;
		this.repository = repository;
		this.relationService = relationService;
		this.relationQueryService = relationQueryService;
		this.workspaceQueryService = workspaceQueryService;
	}

	public record CreateCommand(String title, String summary, String approachMd, String stepsMd, String verificationMd,
			String tradeoffsMd, Instant resolvedAt, UUID errorNodeId, List<UUID> tagIds) {
	}

	public record UpdateCommand(Long version, String title, String summary, String approachMd, String stepsMd,
			String verificationMd, String tradeoffsMd, Instant resolvedAt, List<UUID> tagIds) {
	}

	@Transactional
	public SolutionDetail create(UUID userId, CreateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		String approach = FieldRules.required("approachMd", command.approachMd(), MAX_TEXT_BYTES);
		String steps = FieldRules.optional("stepsMd", command.stepsMd(), MAX_TEXT_BYTES);
		String verification = FieldRules.optional("verificationMd", command.verificationMd(), MAX_TEXT_BYTES);
		String tradeoffs = FieldRules.optional("tradeoffsMd", command.tradeoffsMd(), MAX_TEXT_BYTES);
		Instant resolvedAt = command.resolvedAt() == null ? null : command.resolvedAt().truncatedTo(ChronoUnit.MICROS);

		NodeDetail node = nodeCommandService.createSubtypeNode(userId, NodeType.SOLUTION, command.title(),
				command.summary(), command.tagIds(), "SOLUTION_CREATED");
		SolutionRecordJpaEntity record = repository.save(
				new SolutionRecordJpaEntity(node.id(), workspaceId, approach, steps, verification, tradeoffs, resolvedAt));
		if (command.errorNodeId() != null) {
			relationService.createByTypeKey(userId, command.errorNodeId(), node.id(), "SOLVED_BY", null);
			// 관계가 생겼으니 응답의 relations에 보이도록 다시 읽는다.
			node = nodeQueryService.getOfType(userId, node.id(), NodeType.SOLUTION);
		}
		return detail(node, record);
	}

	@Transactional
	public SolutionDetail update(UUID userId, UUID nodeId, UpdateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		SolutionRecordJpaEntity record = require(workspaceId, nodeId);
		String approach = command.approachMd() == null ? record.getApproachMd()
				: FieldRules.required("approachMd", command.approachMd(), MAX_TEXT_BYTES);
		String steps = command.stepsMd() == null ? record.getStepsMd()
				: FieldRules.optional("stepsMd", command.stepsMd(), MAX_TEXT_BYTES);
		String verification = command.verificationMd() == null ? record.getVerificationMd()
				: FieldRules.optional("verificationMd", command.verificationMd(), MAX_TEXT_BYTES);
		String tradeoffs = command.tradeoffsMd() == null ? record.getTradeoffsMd()
				: FieldRules.optional("tradeoffsMd", command.tradeoffsMd(), MAX_TEXT_BYTES);
		Instant resolvedAt = command.resolvedAt() == null ? record.getResolvedAt()
				: command.resolvedAt().truncatedTo(ChronoUnit.MICROS);
		boolean changed = !approach.equals(record.getApproachMd()) || !Objects.equals(steps, record.getStepsMd())
				|| !Objects.equals(verification, record.getVerificationMd())
				|| !Objects.equals(tradeoffs, record.getTradeoffsMd())
				|| !Objects.equals(resolvedAt, record.getResolvedAt());

		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, command.version(), command.title(),
				command.summary(), command.tagIds(), changed, "SOLUTION_UPDATED");
		if (changed) {
			record.edit(approach, steps, verification, tradeoffs, resolvedAt);
		}
		return detail(node, record);
	}

	@Transactional(readOnly = true)
	public SolutionDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		NodeDetail node = nodeQueryService.getOfType(userId, nodeId, NodeType.SOLUTION);
		return detail(node, require(workspaceId, nodeId));
	}

	@Transactional(readOnly = true)
	public PageResponse<SolutionSummary> list(UUID userId, UUID errorId, UUID projectId, UUID tagId, NodeStatus status,
			String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		if (errorId != null) {
			append(where, "exists (select 1 from RelationJpaEntity r, RelationTypeJpaEntity t where r.relationTypeId = t.id "
					+ "and t.key = 'SOLVED_BY' and r.targetNodeId = n.id and r.sourceNodeId = :errorId)");
			params.put("errorId", errorId);
		}
		if (projectId != null) {
			append(where, "exists (select 1 from RelationJpaEntity r, RelationTypeJpaEntity t where r.relationTypeId = t.id "
					+ "and t.key = 'APPLIED_IN' and r.sourceNodeId = n.id and r.targetNodeId = :projectId)");
			params.put("projectId", projectId);
		}
		if (tagId != null) {
			append(where, "exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
			params.put("tagId", tagId);
		}
		var rows = listQueryService.find(workspaceId, "SolutionRecordJpaEntity", status == null ? NodeStatus.ACTIVE : status,
				where.toString(), params, KeysetPaging.decode(cursor), size + 1);
		boolean hasMore = rows.size() > size;
		var page = hasMore ? rows.subList(0, size) : rows;
		String next = hasMore ? KeysetPaging.encode(page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).id()) : null;

		List<UUID> ids = page.stream().map(SubtypeListQueryService.Row::id).toList();
		Map<UUID, SolutionRecordJpaEntity> records = repository.findByNodeIdIn(ids).stream()
				.collect(Collectors.toMap(SolutionRecordJpaEntity::getNodeId, Function.identity()));
		Map<UUID, Long> errors = relationQueryService.countLinks(workspaceId, ids, "SOLVED_BY", false);
		Map<UUID, Long> snippets = relationQueryService.countLinks(workspaceId, ids, "IMPLEMENTED_WITH", true);
		Map<UUID, Long> projects = relationQueryService.countLinks(workspaceId, ids, "APPLIED_IN", true);
		List<SolutionSummary> items = new ArrayList<>();
		for (NodeSummary n : nodeQueryService.summariesInOrder(userId, ids)) {
			SolutionRecordJpaEntity r = records.get(n.id());
			items.add(new SolutionSummary(n.id(), n.title(), n.summary(), n.status(), n.version(), n.tags(),
					n.favorite(), n.updatedAt(), ErrorService.preview(r.getApproachMd()), r.getResolvedAt(),
					errors.getOrDefault(n.id(), 0L), snippets.getOrDefault(n.id(), 0L),
					projects.getOrDefault(n.id(), 0L)));
		}
		return new PageResponse<>(items, next, hasMore);
	}

	private SolutionRecordJpaEntity require(UUID workspaceId, UUID nodeId) {
		return repository.findByNodeIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
	}

	private static SolutionDetail detail(NodeDetail node, SolutionRecordJpaEntity r) {
		return new SolutionDetail(node.id(), node.type(), node.title(), node.summary(), node.status(), node.version(),
				node.tags(), node.favorite(), node.createdAt(), node.updatedAt(),
				new SolutionData(r.getApproachMd(), r.getStepsMd(), r.getVerificationMd(), r.getTradeoffsMd(),
						r.getResolvedAt()),
				node.relations());
	}

	private static void append(StringBuilder where, String clause) {
		if (!where.isEmpty()) {
			where.append(" and ");
		}
		where.append(clause);
	}
}
