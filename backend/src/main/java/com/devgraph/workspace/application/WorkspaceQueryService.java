package com.devgraph.workspace.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.devgraph.workspace.infrastructure.WorkspaceMemberRepository;
import com.devgraph.workspace.infrastructure.WorkspaceRepository;

@Service
public class WorkspaceQueryService {

	private final WorkspaceRepository workspaceRepository;
	private final WorkspaceMemberRepository memberRepository;

	public WorkspaceQueryService(WorkspaceRepository workspaceRepository, WorkspaceMemberRepository memberRepository) {
		this.workspaceRepository = workspaceRepository;
		this.memberRepository = memberRepository;
	}

	/** MVP는 사용자당 개인 Workspace 1개다(§18.1) — 첫 번째 멤버십을 그 Workspace로 취급한다. */
	public Optional<WorkspaceSummary> findPrimaryWorkspaceForUser(UUID userId) {
		return memberRepository.findByUserId(userId).stream()
				.findFirst()
				.flatMap(member -> workspaceRepository.findById(member.getWorkspaceId()))
				.map(ws -> new WorkspaceSummary(ws.getId(), ws.getName(), ws.getSlug()));
	}
}
