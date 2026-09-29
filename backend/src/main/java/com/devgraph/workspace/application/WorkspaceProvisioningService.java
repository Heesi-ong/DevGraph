package com.devgraph.workspace.application;

import java.security.SecureRandom;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.devgraph.workspace.infrastructure.WorkspaceJpaEntity;
import com.devgraph.workspace.infrastructure.WorkspaceMemberJpaEntity;
import com.devgraph.workspace.infrastructure.WorkspaceMemberRepository;
import com.devgraph.workspace.infrastructure.WorkspaceRepository;

/**
 * 설계서 §11.1/§12.3: 개인 Workspace 생성. 가입 흐름(auth 모듈)이 이 application service를 통해서만
 * 호출한다 — 다른 모듈의 JPA repository에 직접 접근하지 않는다(§15.2).
 */
@Service
public class WorkspaceProvisioningService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final WorkspaceRepository workspaceRepository;
	private final WorkspaceMemberRepository memberRepository;

	public WorkspaceProvisioningService(WorkspaceRepository workspaceRepository,
			WorkspaceMemberRepository memberRepository) {
		this.workspaceRepository = workspaceRepository;
		this.memberRepository = memberRepository;
	}

	/** 개인 Workspace를 생성하고 OWNER로 가입시킨다. 호출자(SignupService)의 트랜잭션 경계 안에서 실행된다. */
	public WorkspaceSummary createPersonalWorkspace(UUID ownerId, String displayName) {
		WorkspaceJpaEntity workspace = new WorkspaceJpaEntity(UUID.randomUUID(), displayName + "의 Workspace",
				generateUniqueSlug());
		workspaceRepository.save(workspace);
		memberRepository.save(new WorkspaceMemberJpaEntity(workspace.getId(), ownerId));
		return new WorkspaceSummary(workspace.getId(), workspace.getName(), workspace.getSlug());
	}

	private String generateUniqueSlug() {
		String slug;
		do {
			slug = "ws-" + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE);
		} while (workspaceRepository.existsBySlug(slug));
		return slug;
	}
}
