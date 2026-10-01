package com.devgraph.resource.application;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import com.devgraph.resource.application.ResourceViews.DuplicateRef;
import com.devgraph.resource.application.ResourceViews.ResourceData;
import com.devgraph.resource.application.ResourceViews.ResourceDetail;
import com.devgraph.resource.application.ResourceViews.ResourceSummary;
import com.devgraph.resource.domain.UrlNormalizer;
import com.devgraph.resource.infrastructure.ResourceDuplicateQuery;
import com.devgraph.resource.infrastructure.ResourceJpaEntity;
import com.devgraph.resource.infrastructure.ResourceRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §12.3/§17.4 Resource(문서·링크·저장소) 노드. URL은 http/https만, 계정 정보가 든 URL은 거부한다.
 * 같은 URL(정규화 기준)이 이미 있으면 **경고(`duplicates`)만 하고 저장은 막지 않는다** — 같은 문서를 다른 맥락으로 기록할 수 있다.
 * 서버는 URL을 가져오지 않는다(SSRF 정책 없이 preview를 만들지 않음, §17.4).
 */
@Service
public class ResourceService {

	private static final int MAX_URL = 2000;
	private static final int MAX_SITE_NAME = 200;
	private static final List<String> KINDS = List.of("WEB", "DOC", "VIDEO", "REPO", "BOOK", "OTHER");

	private final NodeCommandService nodeCommandService;
	private final NodeQueryService nodeQueryService;
	private final SubtypeListQueryService listQueryService;
	private final ResourceRepository repository;
	private final ResourceDuplicateQuery duplicateQuery;
	private final WorkspaceQueryService workspaceQueryService;

	public ResourceService(NodeCommandService nodeCommandService, NodeQueryService nodeQueryService,
			SubtypeListQueryService listQueryService, ResourceRepository repository,
			ResourceDuplicateQuery duplicateQuery, WorkspaceQueryService workspaceQueryService) {
		this.nodeCommandService = nodeCommandService;
		this.nodeQueryService = nodeQueryService;
		this.listQueryService = listQueryService;
		this.repository = repository;
		this.duplicateQuery = duplicateQuery;
		this.workspaceQueryService = workspaceQueryService;
	}

	public record CreateCommand(String title, String summary, String url, String kind, String siteName,
			List<UUID> tagIds) {
	}

	public record UpdateCommand(Long version, String title, String summary, String url, String kind, String siteName,
			List<UUID> tagIds) {
	}

	@Transactional
	public ResourceDetail create(UUID userId, CreateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		String url = FieldRules.httpUrl("url", command.url(), MAX_URL);
		String kind = kind(command.kind(), "WEB");
		String normalized = normalized(url);
		String siteName = siteName(command.siteName(), url);

		NodeDetail node = nodeCommandService.createSubtypeNode(userId, NodeType.RESOURCE, command.title(),
				command.summary(), command.tagIds(), "RESOURCE_CREATED");
		ResourceJpaEntity record = repository.save(new ResourceJpaEntity(node.id(), workspaceId, url, normalized, kind, siteName));
		return detail(node, record, duplicates(workspaceId, normalized, node.id()));
	}

	@Transactional
	public ResourceDetail update(UUID userId, UUID nodeId, UpdateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		ResourceJpaEntity record = require(workspaceId, nodeId);
		String url = command.url() == null ? record.getUrl() : FieldRules.httpUrl("url", command.url(), MAX_URL);
		String kind = command.kind() == null ? record.getResourceKind() : kind(command.kind(), record.getResourceKind());
		String normalized = command.url() == null ? record.getUrlNormalized() : normalized(url);
		String siteName = command.siteName() == null ? (command.url() == null ? record.getSiteName() : siteName(null, url))
				: siteName(command.siteName(), url);
		boolean changed = !url.equals(record.getUrl()) || !kind.equals(record.getResourceKind())
				|| !Objects.equals(siteName, record.getSiteName());

		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, command.version(), command.title(),
				command.summary(), command.tagIds(), changed, "RESOURCE_UPDATED");
		if (changed) {
			record.edit(url, normalized, kind, siteName);
		}
		return detail(node, record, duplicates(workspaceId, normalized, nodeId));
	}

	@Transactional(readOnly = true)
	public ResourceDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		NodeDetail node = nodeQueryService.getOfType(userId, nodeId, NodeType.RESOURCE);
		ResourceJpaEntity record = require(workspaceId, nodeId);
		return detail(node, record, duplicates(workspaceId, record.getUrlNormalized(), nodeId));
	}

	@Transactional(readOnly = true)
	public PageResponse<ResourceSummary> list(UUID userId, List<String> kinds, UUID tagId, NodeStatus status,
			String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		StringBuilder where = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		if (kinds != null && !kinds.isEmpty()) {
			List<String> upper = kinds.stream().map(k -> k.toUpperCase(Locale.ROOT)).toList();
			if (!KINDS.containsAll(upper)) {
				throw FieldRules.validation("kind", "INVALID_KIND");
			}
			where.append("x.resourceKind in :kinds");
			params.put("kinds", upper);
		}
		if (tagId != null) {
			if (!where.isEmpty()) {
				where.append(" and ");
			}
			where.append("exists (select 1 from NodeTagJpaEntity nt where nt.nodeId = n.id and nt.tagId = :tagId)");
			params.put("tagId", tagId);
		}
		var rows = listQueryService.find(workspaceId, "ResourceJpaEntity", status == null ? NodeStatus.ACTIVE : status,
				where.toString(), params, KeysetPaging.decode(cursor), size + 1);
		boolean hasMore = rows.size() > size;
		var page = hasMore ? rows.subList(0, size) : rows;
		String next = hasMore ? KeysetPaging.encode(page.get(page.size() - 1).updatedAt(), page.get(page.size() - 1).id()) : null;

		List<UUID> ids = page.stream().map(SubtypeListQueryService.Row::id).toList();
		Map<UUID, ResourceJpaEntity> records = repository.findByNodeIdIn(ids).stream()
				.collect(Collectors.toMap(ResourceJpaEntity::getNodeId, Function.identity()));
		List<ResourceSummary> items = new ArrayList<>();
		for (NodeSummary n : nodeQueryService.summariesInOrder(userId, ids)) {
			ResourceJpaEntity r = records.get(n.id());
			items.add(new ResourceSummary(n.id(), n.title(), n.summary(), n.status(), n.version(), n.tags(),
					n.favorite(), n.updatedAt(), r.getUrl(), r.getResourceKind(), r.getSiteName()));
		}
		return new PageResponse<>(items, next, hasMore);
	}

	// ---- helpers ---------------------------------------------------------------------------

	private ResourceJpaEntity require(UUID workspaceId, UUID nodeId) {
		return repository.findByNodeIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
	}

	private static String kind(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String upper = value.trim().toUpperCase(Locale.ROOT);
		if (!KINDS.contains(upper)) {
			throw FieldRules.validation("kind", "INVALID_KIND");
		}
		return upper;
	}

	private static String normalized(String url) {
		try {
			return UrlNormalizer.normalize(url);
		} catch (IllegalArgumentException e) {
			throw FieldRules.validation("url", "INVALID_URL");
		}
	}

	/** 사이트 이름을 안 주면 host를 쓴다. */
	private static String siteName(String value, String url) {
		String given = FieldRules.optional("siteName", value, MAX_SITE_NAME);
		if (given != null) {
			return given;
		}
		String host = URI.create(url).getHost();
		return host == null ? null : host.toLowerCase(Locale.ROOT);
	}

	private List<DuplicateRef> duplicates(UUID workspaceId, String normalized, UUID self) {
		return duplicateQuery.find(workspaceId, normalized, self).stream()
				.map(d -> new DuplicateRef(d.id(), d.title())).toList();
	}

	private static ResourceDetail detail(NodeDetail node, ResourceJpaEntity r, List<DuplicateRef> duplicates) {
		return new ResourceDetail(node.id(), node.type(), node.title(), node.summary(), node.status(), node.version(),
				node.tags(), node.favorite(), node.createdAt(), node.updatedAt(),
				new ResourceData(r.getUrl(), r.getResourceKind(), r.getSiteName(), r.getLastCheckedAt()), duplicates,
				node.relations());
	}
}
