package com.devgraph.knowledge.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiException;
import com.devgraph.knowledge.infrastructure.FavoriteRepository;
import com.devgraph.knowledge.infrastructure.KnowledgeNodeRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/** 설계서 §9.2 KNOW-07, §14.3 PUT/DELETE /nodes/{id}/favorite. 둘 다 멱등이다. */
@Service
public class FavoriteService {

	private final FavoriteRepository favoriteRepository;
	private final KnowledgeNodeRepository nodeRepository;
	private final WorkspaceQueryService workspaceQueryService;

	public FavoriteService(FavoriteRepository favoriteRepository, KnowledgeNodeRepository nodeRepository,
			WorkspaceQueryService workspaceQueryService) {
		this.favoriteRepository = favoriteRepository;
		this.nodeRepository = nodeRepository;
		this.workspaceQueryService = workspaceQueryService;
	}

	@Transactional
	public void add(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		nodeRepository.findByIdAndWorkspaceId(nodeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
		favoriteRepository.insertIfAbsent(workspaceId, userId, nodeId);
	}

	@Transactional
	public void remove(UUID userId, UUID nodeId) {
		favoriteRepository.deleteFavorite(userId, nodeId);
	}
}
