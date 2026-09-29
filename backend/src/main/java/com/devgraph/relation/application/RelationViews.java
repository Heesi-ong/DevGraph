package com.devgraph.relation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 설계서 §14.5/§14.7 Relation 응답 모양. */
public final class RelationViews {

	private RelationViews() {
	}

	public record RelationTypeView(UUID id, String key, String forwardLabel, String inverseLabel, String description,
			String directionality, List<String> allowedSourceTypes, List<String> allowedTargetTypes) {
	}

	/** 생성/수정 응답. `type`은 relation type key. */
	public record RelationView(UUID id, UUID sourceNodeId, UUID targetNodeId, UUID relationTypeId, String type,
			String note, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * Node 상세의 한 줄. 대칭 타입은 저장 방향(canonical)과 무관하게 항상 `outgoing`에 담고 상대 Node를
	 * `otherNode*`로 준다 — 화면이 "어느 쪽이 source인가"를 알 필요가 없다.
	 */
	public record RelationLink(UUID id, UUID relationTypeId, String type, String label, UUID nodeId, String nodeTitle,
			String nodeType, String nodeStatus, String note) {
	}

	public record NodeRelations(List<RelationLink> outgoing, List<RelationLink> incoming, boolean truncated) {

		public static NodeRelations empty() {
			return new NodeRelations(List.of(), List.of(), false);
		}
	}

	public record TargetCandidate(UUID id, String title, String type, String status) {
	}
}
