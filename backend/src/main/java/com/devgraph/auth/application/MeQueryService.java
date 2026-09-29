package com.devgraph.auth.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiException;
import com.devgraph.workspace.application.WorkspaceQueryService;
import com.devgraph.workspace.application.WorkspaceSummary;

/** 설계서 §14.2 GET /auth/me. */
@Service
public class MeQueryService {

	private final UserRepository userRepository;
	private final WorkspaceQueryService workspaceQueryService;

	public MeQueryService(UserRepository userRepository, WorkspaceQueryService workspaceQueryService) {
		this.userRepository = userRepository;
		this.workspaceQueryService = workspaceQueryService;
	}

	public record Me(UserJpaEntity user, WorkspaceSummary workspace) {
	}

	public Me getMe(UUID userId) {
		UserJpaEntity user = userRepository.findById(userId)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "인증이 필요합니다."));
		WorkspaceSummary workspace = workspaceQueryService.findPrimaryWorkspaceForUser(userId).orElse(null);
		return new Me(user, workspace);
	}
}
