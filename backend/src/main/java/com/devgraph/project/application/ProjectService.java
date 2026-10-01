package com.devgraph.project.application;

import java.time.LocalDate;
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
import com.devgraph.graph.application.GraphService;
import com.devgraph.graph.application.GraphViews.GraphResponse;
import com.devgraph.knowledge.application.NodeCommandService;
import com.devgraph.knowledge.application.NodeDetail;
import com.devgraph.knowledge.application.NodeQueryService;
import com.devgraph.knowledge.application.NodeSummary;
import com.devgraph.knowledge.application.SubtypeListQueryService;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.project.application.ProjectViews.ProjectData;
import com.devgraph.project.application.ProjectViews.ProjectDetail;
import com.devgraph.project.application.ProjectViews.ProjectSummary;
import com.devgraph.project.domain.ProjectStatus;
import com.devgraph.project.infrastructure.ProjectRecordJpaEntity;
import com.devgraph.project.infrastructure.ProjectRecordRepository;
import com.devgraph.relation.application.RelationQueryService;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.6 PROJ-01~04. Project 생성·수정·목록과 프로젝트 범위 그래프. Knowledge/Error/Solution과의 연결은
 * 별도 junction이 아니라 Relation(`USED_IN`/`OCCURRED_IN`/`APPLIED_IN`)이 단일 모델이다(PROJ-03).
 */
@Service
public class ProjectService {

	static final int DEFAULT_GRAPH_DEPTH = 2;
	private static final int MAX_REPOSITORY_URL = 500;

	private final NodeCommandService nodeCommandService;
	private final NodeQueryService nodeQueryService;
	private final SubtypeListQueryService listQueryService;
	private final ProjectRecordRepository repository;
	private final RelationQueryService relationQueryService;
	private final GraphService graphService;
	private final WorkspaceQueryService workspaceQueryService;

	public ProjectService(NodeCommandService nodeCommandService, NodeQueryService nodeQueryService,
			SubtypeListQueryService listQueryService, ProjectRecordRepository repository,
			RelationQueryService relationQueryService, GraphService graphService,
			WorkspaceQueryService workspaceQueryService) {
		this.nodeCommandService = nodeCommandService;
		this.nodeQueryService = nodeQueryService;
		this.listQueryService = listQueryService;
		this.repository = repository;
		this.relationQueryService = relationQueryService;
		this.graphService = graphService;
		this.workspaceQueryService = workspaceQueryService;
	}

	public record CreateCommand(String title, String summary, String description, ProjectStatus projectStatus,
			String repositoryUrl, LocalDate startedOn, LocalDate endedOn, List<UUID> tagIds) {
	}

	/** null은 변경 없음, `repositoryUrl`의 빈 문자열은 지움. 날짜는 null이 변경 없음(지우려면 `clearDates`). */
	public record UpdateCommand(Long version, String title, String summary, String description,
			ProjectStatus projectStatus, String repositoryUrl, LocalDate startedOn, LocalDate endedOn,
			boolean clearStartedOn, boolean clearEndedOn, List<UUID> tagIds) {
	}

	@Transactional
	public ProjectDetail create(UUID userId, CreateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		ProjectStatus status = command.projectStatus() == null ? ProjectStatus.ACTIVE : command.projectStatus();
		String url = optionalUrl(command.repositoryUrl());
		requirePeriod(command.startedOn(), command.endedOn());

		NodeDetail node = nodeCommandService.createSubtypeNode(userId, NodeType.PROJECT, command.title(),
				command.summary(), command.description(), command.tagIds(), "PROJECT_CREATED");
		ProjectRecordJpaEntity record = repository.save(new ProjectRecordJpaEntity(node.id(), workspaceId, status, url,
				command.startedOn(), command.endedOn()));
		return detail(node, record);
	}

	@Transactional
	public ProjectDetail update(UUID userId, UUID nodeId, UpdateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		ProjectRecordJpaEntity record = require(workspaceId, nodeId);
		ProjectStatus status = command.projectStatus() == null ? record.getProjectStatus() : command.projectStatus();
		String url = command.repositoryUrl() == null ? record.getRepositoryUrl() : optionalUrl(command.repositoryUrl());
		LocalDate started = command.clearStartedOn() ? null
				: (command.startedOn() == null ? record.getStartedOn() : command.startedOn());
		LocalDate ended = command.clearEndedOn() ? null
				: (command.endedOn() == null ? record.getEndedOn() : command.endedOn());
		requirePeriod(started, ended);
		boolean changed = status != record.getProjectStatus() || !Objects.equals(url, record.getRepositoryUrl())
				|| !Objects.equals(started, record.getStartedOn()) || !Objects.equals(ended, record.getEndedOn());

		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, command.version(), command.title(),
				command.summary(), command.description(), command.tagIds(), changed, "PROJECT_UPDATED");
		if (changed) {
			record.edit(status, url, started, ended);
		}
		return detail(node, record);
	}

	@Transactional(readOnly = true)
	public ProjectDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		NodeDetail node = nodeQueryService.getOfType(userId, nodeId, NodeType.PROJECT);
		return detail(node, require(workspaceId, nodeId));
	}

	/** PROJ-04 Project Graph: 프로젝트를 중심으로 한 focus 그래프(기본 depth 2). 같은 상한·잘림 규칙(§13.3)이 적용된다. */
	@Transactional(readOnly = true)
	public GraphResponse graph(UUID userId, UUID nodeId, Integer depth, List<NodeType> nodeTypes,
			List<String> relationTypes, boolean includeArchived) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		require(workspaceId, nodeId);
		return graphService.focus(userId, nodeId, depth == null ? DEFAULT_GRAPH_DEPTH : depth, nodeTypes, relationTypes,
				null, includeArchived);
	}

	@Transactional(readOnly = true)
	public PageResponse<ProjectSummary> list(UUID userId, List<ProjectStatus> projectStatus, UUID tagId,
			NodeStatus status, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		if (projectStatus != null && !projectStatus.isEmpty()) {
			where.append("x.projectStatus in :projectStatus");
			params.put("projectStatus", projectStatus.stream().map(Enum::name).toList());
		}
		if (tagId != null) {
			if (!where.isEmpty()) {
				where.append(" and ");
			}
			where.append("exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
			params.put("tagId", tagId);
		}
		var rows = listQueryService.find(workspaceId, "ProjectRecordJpaEntity", status == null ? NodeStatus.ACTIVE : status,
				where.toString(), params, KeysetPaging.decode(cursor), size + 1);
		boolean hasMore = rows.size() > size;
		var page = hasMore ? rows.subList(0, size) : rows;
		String next = hasMore ? KeysetPaging.encode(page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).id()) : null;

		List<UUID> ids = page.stream().map(SubtypeListQueryService.Row::id).toList();
		Map<UUID, ProjectRecordJpaEntity> records = repository.findByNodeIdIn(ids).stream()
				.collect(Collectors.toMap(ProjectRecordJpaEntity::getNodeId, Function.identity()));
		Map<UUID, Long> problems = relationQueryService.countLinks(workspaceId, ids, "OCCURRED_IN", false);
		Map<UUID, Long> knowledge = relationQueryService.countLinks(workspaceId, ids, "USED_IN", false);
		Map<UUID, Long> solutions = relationQueryService.countLinks(workspaceId, ids, "APPLIED_IN", false);
		List<ProjectSummary> items = new ArrayList<>();
		for (NodeSummary n : nodeQueryService.summariesInOrder(userId, ids)) {
			ProjectRecordJpaEntity r = records.get(n.id());
			items.add(new ProjectSummary(n.id(), n.title(), n.summary(), n.status(), n.version(), n.tags(), n.favorite(),
					n.updatedAt(), r.getProjectStatus().name(), r.getRepositoryUrl(), r.getStartedOn(), r.getEndedOn(),
					problems.getOrDefault(n.id(), 0L), knowledge.getOrDefault(n.id(), 0L),
					solutions.getOrDefault(n.id(), 0L)));
		}
		return new PageResponse<>(items, next, hasMore);
	}

	// ---- helpers ---------------------------------------------------------------------------

	private ProjectRecordJpaEntity require(UUID workspaceId, UUID nodeId) {
		return repository.findByNodeIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
	}

	private static String optionalUrl(String value) {
		return value == null || value.isBlank() ? null : FieldRules.httpUrl("repositoryUrl", value, MAX_REPOSITORY_URL);
	}

	private static void requirePeriod(LocalDate started, LocalDate ended) {
		if (started != null && ended != null && ended.isBefore(started)) {
			throw FieldRules.validation("endedOn", "BEFORE_STARTED_ON");
		}
	}

	private static ProjectDetail detail(NodeDetail node, ProjectRecordJpaEntity r) {
		return new ProjectDetail(node.id(), node.type(), node.title(), node.summary(), node.bodyMd(), node.status(),
				node.version(), node.tags(), node.favorite(), node.createdAt(), node.updatedAt(),
				new ProjectData(r.getProjectStatus().name(), r.getRepositoryUrl(), r.getStartedOn(), r.getEndedOn()),
				node.relations());
	}
}
