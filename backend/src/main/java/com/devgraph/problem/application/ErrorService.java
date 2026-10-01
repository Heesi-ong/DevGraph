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
import com.devgraph.problem.application.ProblemViews.ErrorData;
import com.devgraph.problem.application.ProblemViews.ErrorDetail;
import com.devgraph.problem.application.ProblemViews.ErrorSummary;
import com.devgraph.problem.domain.ResolutionStatus;
import com.devgraph.problem.infrastructure.ErrorRecordJpaEntity;
import com.devgraph.problem.infrastructure.ErrorRecordRepository;
import com.devgraph.relation.application.RelationQueryService;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.7 ERR-01/02. Error 생성·수정과 상태 전환. 공통 필드(제목·요약·태그·상태)는 knowledge 응용 서비스에 맡긴다.
 * `RESOLVED`로 바꿀 때 Solution(`SOLVED_BY`) 연결이 없으면 경고(`NO_SOLUTION_LINKED`)하지만 저장은 막지 않는다.
 */
@Service
public class ErrorService {

	static final int MAX_TEXT_BYTES = 100_000;
	static final int MAX_ENVIRONMENT = 500;
	static final String NO_SOLUTION_LINKED = "NO_SOLUTION_LINKED";

	private final NodeCommandService nodeCommandService;
	private final NodeQueryService nodeQueryService;
	private final SubtypeListQueryService listQueryService;
	private final ErrorRecordRepository repository;
	private final RelationQueryService relationQueryService;
	private final WorkspaceQueryService workspaceQueryService;

	public ErrorService(NodeCommandService nodeCommandService, NodeQueryService nodeQueryService,
			SubtypeListQueryService listQueryService, ErrorRecordRepository repository,
			RelationQueryService relationQueryService, WorkspaceQueryService workspaceQueryService) {
		this.nodeCommandService = nodeCommandService;
		this.nodeQueryService = nodeQueryService;
		this.listQueryService = listQueryService;
		this.repository = repository;
		this.relationQueryService = relationQueryService;
		this.workspaceQueryService = workspaceQueryService;
	}

	public record CreateCommand(String title, String summary, String errorMessage, String environment,
			String reproductionStepsMd, String causeHypothesisMd, Instant occurredAt, List<UUID> tagIds) {
	}

	/** null은 변경 없음, 선택 텍스트의 빈 문자열은 지움. */
	public record UpdateCommand(Long version, String title, String summary, String errorMessage, String environment,
			String reproductionStepsMd, String causeHypothesisMd, Instant occurredAt, List<UUID> tagIds) {
	}

	@Transactional
	public ErrorDetail create(UUID userId, CreateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		String message = FieldRules.required("errorMessage", command.errorMessage(), MAX_TEXT_BYTES);
		String environment = FieldRules.optional("environment", command.environment(), MAX_ENVIRONMENT);
		String repro = FieldRules.optional("reproductionStepsMd", command.reproductionStepsMd(), MAX_TEXT_BYTES);
		String cause = FieldRules.optional("causeHypothesisMd", command.causeHypothesisMd(), MAX_TEXT_BYTES);
		Instant occurredAt = command.occurredAt() == null ? now() : command.occurredAt().truncatedTo(ChronoUnit.MICROS);

		NodeDetail node = nodeCommandService.createSubtypeNode(userId, NodeType.ERROR, command.title(),
				command.summary(), command.tagIds(), "ERROR_CREATED");
		ErrorRecordJpaEntity record = repository
				.save(new ErrorRecordJpaEntity(node.id(), workspaceId, message, environment, repro, cause, occurredAt));
		return detail(node, record);
	}

	@Transactional
	public ErrorDetail update(UUID userId, UUID nodeId, UpdateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		ErrorRecordJpaEntity record = require(workspaceId, nodeId);
		String message = command.errorMessage() == null ? record.getErrorMessage()
				: FieldRules.required("errorMessage", command.errorMessage(), MAX_TEXT_BYTES);
		String environment = command.environment() == null ? record.getEnvironment()
				: FieldRules.optional("environment", command.environment(), MAX_ENVIRONMENT);
		String repro = command.reproductionStepsMd() == null ? record.getReproductionStepsMd()
				: FieldRules.optional("reproductionStepsMd", command.reproductionStepsMd(), MAX_TEXT_BYTES);
		String cause = command.causeHypothesisMd() == null ? record.getCauseHypothesisMd()
				: FieldRules.optional("causeHypothesisMd", command.causeHypothesisMd(), MAX_TEXT_BYTES);
		Instant occurredAt = command.occurredAt() == null ? record.getOccurredAt()
				: command.occurredAt().truncatedTo(ChronoUnit.MICROS);
		boolean changed = !message.equals(record.getErrorMessage())
				|| !Objects.equals(environment, record.getEnvironment())
				|| !Objects.equals(repro, record.getReproductionStepsMd())
				|| !Objects.equals(cause, record.getCauseHypothesisMd())
				|| !occurredAt.equals(record.getOccurredAt());

		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, command.version(), command.title(),
				command.summary(), command.tagIds(), changed, "ERROR_UPDATED");
		if (changed) {
			record.edit(message, environment, repro, cause, occurredAt);
		}
		return detail(node, record);
	}

	/**
	 * 상태 전환(ERR-02). 허용되지 않은 전이는 `409 INVALID_STATUS_TRANSITION`, 같은 상태로의 요청은 아무것도 바꾸지 않는다.
	 * `RESOLVED`가 아니면 `resolvedAt`은 무시하고, `RESOLVED`일 때 생략하면 지금이며 발생 시각보다 이를 수 없다.
	 */
	@Transactional
	public ErrorDetail changeStatus(UUID userId, UUID nodeId, Long version, ResolutionStatus target, Instant resolvedAt) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		ErrorRecordJpaEntity record = require(workspaceId, nodeId);
		if (target == null) {
			throw FieldRules.validation("status", "REQUIRED");
		}
		ResolutionStatus current = record.getResolutionStatus();
		if (current == target) {
			return detail(nodeQueryService.getOfType(userId, nodeId, NodeType.ERROR), record);
		}
		if (!current.canMoveTo(target)) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
					current + "에서 " + target + "(으)로 바로 바꿀 수 없습니다.");
		}
		Instant resolved = null;
		if (target == ResolutionStatus.RESOLVED) {
			resolved = resolvedAt == null ? now() : resolvedAt.truncatedTo(ChronoUnit.MICROS);
			if (resolved.isBefore(record.getOccurredAt())) {
				throw FieldRules.validation("resolvedAt", "BEFORE_OCCURRED_AT");
			}
		}
		// version 확인·휴지통 거부·version 증가는 Node 쪽에서 처리한다.
		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, version, null, null, null, true,
				"ERROR_STATUS_CHANGED");
		record.moveTo(target, resolved);
		return detail(node, record);
	}

	@Transactional(readOnly = true)
	public ErrorDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		NodeDetail node = nodeQueryService.getOfType(userId, nodeId, NodeType.ERROR);
		return detail(node, require(workspaceId, nodeId));
	}

	@Transactional(readOnly = true)
	public PageResponse<ErrorSummary> list(UUID userId, List<ResolutionStatus> resolution, UUID projectId, UUID tagId,
			NodeStatus status, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		if (resolution != null && !resolution.isEmpty()) {
			where.append("x.resolutionStatus in :resolution");
			params.put("resolution", resolution.stream().map(Enum::name).toList());
		}
		if (projectId != null) {
			append(where, "exists (select 1 from RelationJpaEntity r, RelationTypeJpaEntity t where r.relationTypeId = t.id "
					+ "and t.key = 'OCCURRED_IN' and r.sourceNodeId = n.id and r.targetNodeId = :projectId)");
			params.put("projectId", projectId);
		}
		if (tagId != null) {
			append(where, "exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
			params.put("tagId", tagId);
		}
		var rows = listQueryService.find(workspaceId, "ErrorRecordJpaEntity", status == null ? NodeStatus.ACTIVE : status,
				where.toString(), params, KeysetPaging.decode(cursor), size + 1);
		boolean hasMore = rows.size() > size;
		var page = hasMore ? rows.subList(0, size) : rows;
		String next = hasMore ? KeysetPaging.encode(page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).id()) : null;

		List<UUID> ids = page.stream().map(SubtypeListQueryService.Row::id).toList();
		Map<UUID, ErrorRecordJpaEntity> records = repository.findByNodeIdIn(ids).stream()
				.collect(Collectors.toMap(ErrorRecordJpaEntity::getNodeId, Function.identity()));
		Map<UUID, Long> solutions = relationQueryService.countLinks(workspaceId, ids, "SOLVED_BY", true);
		Map<UUID, Long> projects = relationQueryService.countLinks(workspaceId, ids, "OCCURRED_IN", true);
		List<ErrorSummary> items = new ArrayList<>();
		for (NodeSummary n : nodeQueryService.summariesInOrder(userId, ids)) {
			ErrorRecordJpaEntity r = records.get(n.id());
			items.add(new ErrorSummary(n.id(), n.title(), n.summary(), n.status(), n.version(), n.tags(), n.favorite(),
					n.updatedAt(), preview(r.getErrorMessage()), r.getResolutionStatus().name(), r.getOccurredAt(),
					r.getResolvedAt(), solutions.getOrDefault(n.id(), 0L), projects.getOrDefault(n.id(), 0L)));
		}
		return new PageResponse<>(items, next, hasMore);
	}

	// ---- helpers ---------------------------------------------------------------------------

	private ErrorRecordJpaEntity require(UUID workspaceId, UUID nodeId) {
		return repository.findByNodeIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
	}

	private static ErrorDetail detail(NodeDetail node, ErrorRecordJpaEntity r) {
		List<String> warnings = new ArrayList<>();
		boolean linked = node.relations().outgoing().stream().anyMatch(l -> "SOLVED_BY".equals(l.type()));
		if (r.getResolutionStatus() == ResolutionStatus.RESOLVED && !linked) {
			warnings.add(NO_SOLUTION_LINKED);
		}
		return new ErrorDetail(node.id(), node.type(), node.title(), node.summary(), node.status(), node.version(),
				node.tags(), node.favorite(), node.createdAt(), node.updatedAt(),
				new ErrorData(r.getErrorMessage(), r.getEnvironment(), r.getReproductionStepsMd(),
						r.getCauseHypothesisMd(), r.getResolutionStatus().name(), r.getOccurredAt(), r.getResolvedAt()),
				node.relations(), warnings);
	}

	static String preview(String text) {
		String oneLine = text.strip().replaceAll("\\s+", " ");
		return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
	}

	private static void append(StringBuilder where, String clause) {
		if (!where.isEmpty()) {
			where.append(" and ");
		}
		where.append(clause);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
