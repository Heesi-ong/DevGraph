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

	/**
	 * 설계서 §14.1: Workspace id는 client가 보내지 않고 인증 principal에서 서버가 결정한다.
	 * 멤버십이 없으면 데이터 접근 자체를 막는다.
	 */
	public UUID requireWorkspaceId(UUID userId) {
		return findPrimaryWorkspaceForUser(userId)
				.map(WorkspaceSummary::id)
				.orElseThrow(() -> new com.devgraph.common.error.ApiException(
						org.springframework.http.HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다."));
	}

	/** MVP는 사용자당 개인 Workspace 1개다(§18.1) — 첫 번째 멤버십을 그 Workspace로 취급한다. */
	public Optional<WorkspaceSummary> findPrimaryWorkspaceForUser(UUID userId) {
		return memberRepository.findByUserId(userId).stream()
				.findFirst()
				.flatMap(member -> workspaceRepository.findById(member.getWorkspaceId()))
				.map(ws -> new WorkspaceSummary(ws.getId(), ws.getName(), ws.getSlug()));
	}
}
