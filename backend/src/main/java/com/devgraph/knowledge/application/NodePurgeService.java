package com.devgraph.knowledge.application;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.auth.application.ReauthPurpose;
import com.devgraph.auth.application.ReauthService;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §11.3 DATA-03 영구 삭제와 30일 자동 purge. 같은 삭제 유스케이스를 둘이 공유한다.
 * subtype, tag 매핑, favorites, node_views, relations는 DB cascade(FK ON DELETE CASCADE)로 함께 지워진다.
 * ActivityLog에는 삭제된 object id와 action만 남기고 제목 같은 본문은 남기지 않는다.
 */
@Service
public class NodePurgeService {

	private final JdbcTemplate jdbc;
	private final ReauthService reauthService;
	private final WorkspaceQueryService workspaceQueryService;

	public NodePurgeService(JdbcTemplate jdbc, ReauthService reauthService,
			WorkspaceQueryService workspaceQueryService) {
		this.jdbc = jdbc;
		this.reauthService = reauthService;
		this.workspaceQueryService = workspaceQueryService;
	}

	/**
	 * 사용자 영구 삭제. 휴지통(`TRASHED`)에 있는 Node만 가능하다(`409 INVALID_NODE_STATE`). 상태 검사를 재인증 소비보다
	 * 먼저 해서, 지울 수 없는 대상을 눌렀다고 1회용 토큰이 소진되지 않게 한다.
	 */
	@Transactional
	public void purgeByUser(AuthenticatedUser actor, UUID nodeId, String reauthToken, String ipPrefix) {
		// 토큰 검증(소비 안 함) → 대상 확인 → 소비 순서. 잘못된 토큰은 대상 상태를 드러내지 않고, 전제 실패는 토큰을 태우지 않는다.
		ReauthService.Verified verified = reauthService.verify(actor, reauthToken, ReauthPurpose.NODE_PERMANENT_DELETE, nodeId,
				ipPrefix);
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(actor.userId());
		List<String> status = jdbc.queryForList("SELECT status FROM knowledge_nodes WHERE id = ? AND workspace_id = ?",
				String.class, nodeId, workspaceId);
		if (status.isEmpty()) {
			throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
		}
		if (!"TRASHED".equals(status.get(0))) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_NODE_STATE", "휴지통에 있는 항목만 영구 삭제할 수 있습니다.");
		}
		reauthService.consume(verified, actor, ReauthPurpose.NODE_PERMANENT_DELETE, ipPrefix);
		int deleted = jdbc.update("DELETE FROM knowledge_nodes WHERE id = ? AND workspace_id = ? AND status = 'TRASHED'",
				nodeId, workspaceId);
		if (deleted == 0) {
			// 재인증 확인 사이에 복구되었다. 삭제하지 않는다.
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_NODE_STATE", "휴지통에 있는 항목만 영구 삭제할 수 있습니다.");
		}
		logPurge(workspaceId, actor.userId(), "NODE_PURGED", nodeId);
	}

	/**
	 * 보존 기간(기본 30일, §21.5)이 지난 휴지통 Node를 지운다. 한 번에 `batch`개까지만 처리해 긴 트랜잭션을 피한다.
	 * 사용자 요청이 아니라 시스템 작업이므로 작업자는 Node를 만든 사용자로 기록한다(어떤 사용자의 데이터인지 남기기 위함).
	 */
	@Transactional
	public int purgeExpiredTrash(Instant now, Duration retention, int batch) {
		Timestamp cutoff = Timestamp.from(now.minus(retention));
		List<java.util.Map<String, Object>> expired = jdbc.queryForList("""
				SELECT id, workspace_id, created_by FROM knowledge_nodes
				WHERE status = 'TRASHED' AND trashed_at < ? ORDER BY trashed_at LIMIT ?""", cutoff, batch);
		for (var row : expired) {
			UUID id = (UUID) row.get("id");
			jdbc.update("DELETE FROM knowledge_nodes WHERE id = ? AND status = 'TRASHED'", id);
			logPurge((UUID) row.get("workspace_id"), (UUID) row.get("created_by"), "NODE_PURGED_AUTO", id);
		}
		return expired.size();
	}

	private void logPurge(UUID workspaceId, UUID actorUserId, String action, UUID nodeId) {
		jdbc.update("""
				INSERT INTO activity_logs (workspace_id, actor_user_id, action, object_type, object_id)
				VALUES (?, ?, ?, 'NODE', ?)""", workspaceId, actorUserId, action, nodeId);
	}
}
