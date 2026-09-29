package com.devgraph.relation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiException;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.relation.application.RelationViews.NodeRelations;
import com.devgraph.relation.application.RelationViews.RelationLink;
import com.devgraph.relation.application.RelationViews.RelationTypeView;
import com.devgraph.relation.application.RelationViews.TargetCandidate;
import com.devgraph.relation.infrastructure.RelationQueryRepository;
import com.devgraph.relation.infrastructure.RelationQueryRepository.Row;
import com.devgraph.relation.infrastructure.RelationQueryRepository.Side;
import com.devgraph.relation.infrastructure.RelationTypeJpaEntity;
import com.devgraph.relation.infrastructure.RelationTypeRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/** 설계서 §9.4 REL-03 타입 목록, §14.7 Node 상세의 incoming/outgoing, Relation Picker 후보. */
@Service
public class RelationQueryService {

	/** 한 Node 상세에 싣는 관계 수 상한. 넘으면 `truncated=true`로 알린다(응답을 조용히 자르지 않는다). */
	static final int DETAIL_LIMIT = 200;
	static final int CANDIDATE_LIMIT = 20;
	static final int MAX_QUERY = 100;

	private final RelationTypeRepository typeRepository;
	private final RelationQueryRepository queryRepository;
	private final WorkspaceQueryService workspaceQueryService;

	public RelationQueryService(RelationTypeRepository typeRepository, RelationQueryRepository queryRepository,
			WorkspaceQueryService workspaceQueryService) {
		this.typeRepository = typeRepository;
		this.queryRepository = queryRepository;
		this.workspaceQueryService = workspaceQueryService;
	}

	@Transactional(readOnly = true)
	public List<RelationTypeView> listTypes(UUID userId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		return typeRepository.findAvailable(workspaceId).stream()
				.map(t -> new RelationTypeView(t.getId(), t.getKey(), t.getForwardLabel(), t.getInverseLabel(),
						t.getDescription(), t.getDirectionality(), t.getAllowedSourceTypes(), t.getAllowedTargetTypes()))
				.toList();
	}

	/**
	 * 호출자(knowledge)가 이미 Workspace 범위로 검증한 Node의 관계. 대칭 타입은 저장 방향과 무관하게 `outgoing`에
	 * 담고, 방향성 타입은 이 Node가 source면 outgoing(forward label), target이면 incoming(inverse label)이다.
	 */
	@Transactional(readOnly = true)
	public NodeRelations forNode(UUID workspaceId, UUID nodeId) {
		List<Row> rows = queryRepository.findForNode(workspaceId, nodeId, DETAIL_LIMIT + 1);
		boolean truncated = rows.size() > DETAIL_LIMIT;
		List<RelationLink> outgoing = new ArrayList<>();
		List<RelationLink> incoming = new ArrayList<>();
		for (Row row : rows.subList(0, Math.min(rows.size(), DETAIL_LIMIT))) {
			boolean isSource = row.sourceId().equals(nodeId);
			if (row.symmetric() || isSource) {
				outgoing.add(link(row, row.forwardLabel()));
			} else {
				incoming.add(link(row, row.inverseLabel()));
			}
		}
		return new NodeRelations(outgoing, incoming, truncated);
	}

	/**
	 * Relation Picker 후보. `side=OUTGOING`은 이 Node가 source, `INCOMING`은 target이다.
	 * 이 Node의 타입이 그 방향으로 허용되지 않으면 빈 목록이다(화면은 허용 타입으로 선택지를 미리 거른다).
	 */
	@Transactional(readOnly = true)
	public List<TargetCandidate> candidates(UUID userId, UUID nodeId, UUID relationTypeId, Side side, String q) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		RelationTypeJpaEntity type = typeRepository.findAvailableById(relationTypeId, workspaceId)
				.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RELATION_TYPE", "사용할 수 없는 관계 타입입니다."));
		var self = queryRepository.findNodes(workspaceId, List.of(nodeId)).stream().findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다."));
		List<String> selfSide = side == Side.OUTGOING ? type.getAllowedSourceTypes() : type.getAllowedTargetTypes();
		if (!selfSide.contains(self.type().name())) {
			return List.of();
		}
		List<String> otherSide = side == Side.OUTGOING ? type.getAllowedTargetTypes() : type.getAllowedSourceTypes();
		List<NodeType> allowed = otherSide.stream().map(NodeType::valueOf).toList();
		String query = q == null || q.isBlank() ? null : q.trim();
		if (query != null && query.length() > MAX_QUERY) {
			query = query.substring(0, MAX_QUERY);
		}
		return queryRepository
				.findCandidates(workspaceId, nodeId, type.getId(), side, type.isSymmetric(), allowed, query, CANDIDATE_LIMIT)
				.stream()
				.map(c -> new TargetCandidate(c.id(), c.title(), c.type().name(), c.status().name()))
				.toList();
	}

	private static RelationLink link(Row row, String label) {
		return new RelationLink(row.relationId(), row.typeId(), row.typeKey(), label, row.otherId(), row.otherTitle(),
				row.otherType().name(), row.otherStatus().name(), row.note());
	}
}
