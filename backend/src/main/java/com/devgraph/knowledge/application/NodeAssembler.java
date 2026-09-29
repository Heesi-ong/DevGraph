package com.devgraph.knowledge.application;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.devgraph.knowledge.infrastructure.FavoriteRepository;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeJpaEntity;
import com.devgraph.knowledge.infrastructure.NodeTagJpaEntity;
import com.devgraph.knowledge.infrastructure.NodeTagRepository;
import com.devgraph.tag.application.TagService;
import com.devgraph.tag.application.TagView;

/** 엔티티 묶음에 태그·즐겨찾기를 페이지 단위 2~3개 쿼리로 채운다(노드마다 쿼리하는 N+1을 피한다). */
@Component
public class NodeAssembler {

	private final NodeTagRepository nodeTagRepository;
	private final FavoriteRepository favoriteRepository;
	private final TagService tagService;

	public NodeAssembler(NodeTagRepository nodeTagRepository, FavoriteRepository favoriteRepository,
			TagService tagService) {
		this.nodeTagRepository = nodeTagRepository;
		this.favoriteRepository = favoriteRepository;
		this.tagService = tagService;
	}

	public List<NodeSummary> toSummaries(UUID workspaceId, UUID userId, List<KnowledgeNodeJpaEntity> nodes) {
		if (nodes.isEmpty()) {
			return List.of();
		}
		Extras extras = load(workspaceId, userId, nodes);
		return nodes.stream()
				.map(n -> new NodeSummary(n.getId(), n.getNodeType().name(), n.getTitle(), n.getSummary(),
						n.getStatus().name(), n.getVersion(), extras.tagsOf(n.getId()),
						extras.favorites().contains(n.getId()), n.getCreatedAt(), n.getUpdatedAt()))
				.toList();
	}

	public NodeDetail toDetail(UUID workspaceId, UUID userId, KnowledgeNodeJpaEntity node) {
		Extras extras = load(workspaceId, userId, List.of(node));
		return new NodeDetail(node.getId(), node.getNodeType().name(), node.getTitle(), node.getSummary(),
				node.getBodyMd(), node.getStatus().name(), node.getVersion(), extras.tagsOf(node.getId()),
				extras.favorites().contains(node.getId()), node.getCreatedAt(), node.getUpdatedAt());
	}

	private Extras load(UUID workspaceId, UUID userId, List<KnowledgeNodeJpaEntity> nodes) {
		List<UUID> nodeIds = nodes.stream().map(KnowledgeNodeJpaEntity::getId).toList();
		Map<UUID, List<UUID>> tagIdsByNode = nodeTagRepository.findByNodeIdIn(nodeIds).stream()
				.collect(Collectors.groupingBy(NodeTagJpaEntity::getNodeId,
						Collectors.mapping(NodeTagJpaEntity::getTagId, Collectors.toList())));
		Set<UUID> allTagIds = new HashSet<>();
		tagIdsByNode.values().forEach(allTagIds::addAll);
		Map<UUID, TagView> views = tagService.findViews(workspaceId, allTagIds);
		Set<UUID> favorites = new HashSet<>(favoriteRepository.findFavoriteNodeIds(userId, nodeIds));
		return new Extras(tagIdsByNode, views, favorites);
	}

	private record Extras(Map<UUID, List<UUID>> tagIdsByNode, Map<UUID, TagView> views, Set<UUID> favorites) {

		List<TagView> tagsOf(UUID nodeId) {
			Collection<UUID> ids = tagIdsByNode.getOrDefault(nodeId, List.of());
			return ids.stream()
					.map(views::get)
					.filter(java.util.Objects::nonNull)
					.sorted(Comparator.comparing(TagView::name, String.CASE_INSENSITIVE_ORDER))
					.toList();
		}
	}
}
