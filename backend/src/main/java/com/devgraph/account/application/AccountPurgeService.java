package com.devgraph.account.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.common.security.TokenHasher;

/**
 * 설계서 §21.5: 탈퇴 유예(7일)가 지난 계정의 개인 데이터를 hard delete한다. 여러 모듈의 테이블을 한 트랜잭션으로
 * 지우는 운영용 유스케이스라 SQL로 직접 처리한다. 삭제 순서는 FK 방향을 따른다(콘텐츠 → Workspace → 세션 → 사용자).
 * `security_audit_logs`는 보존 기간(365일)을 따로 두되 사용자 식별자는 지워 익명화한다.
 */
@Service
public class AccountPurgeService {

	private static final Logger log = LoggerFactory.getLogger(AccountPurgeService.class);

	private final JdbcTemplate jdbc;
	private final SecurityAuditService audit;

	public AccountPurgeService(JdbcTemplate jdbc, SecurityAuditService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	/** 유예가 지난 계정을 모두 지운다. 한 계정이 실패해도 다음 계정을 계속한다. 지운 계정 수를 돌려준다. */
	public int purgeDue(Instant now) {
		Instant cutoff = now.minus(AccountService.GRACE_DAYS, ChronoUnit.DAYS);
		List<UUID> due = jdbc.queryForList(
				"SELECT id FROM users WHERE status = 'DELETION_PENDING' AND deletion_requested_at <= ? ORDER BY deletion_requested_at LIMIT 50",
				UUID.class, Timestamp.from(cutoff));
		int purged = 0;
		for (UUID userId : due) {
			try {
				purgeUser(userId);
				purged++;
			} catch (RuntimeException e) {
				// 계정 식별자는 로그에 남기지 않는다(개인정보). 해시 앞부분만으로 운영자가 추적한다.
				log.error("account purge failed userIdHash={}", hash(userId), e);
			}
		}
		return purged;
	}

	@Transactional
	public void purgeUser(UUID userId) {
		List<UUID> workspaces = jdbc.queryForList("SELECT workspace_id FROM workspace_members WHERE user_id = ?", UUID.class, userId);
		for (UUID workspaceId : workspaces) {
			jdbc.update("DELETE FROM export_jobs WHERE workspace_id = ?", workspaceId);
			// Node를 지우면 subtype, tag 매핑, favorites, node_views, relations가 cascade로 함께 지워진다(§11.3).
			jdbc.update("DELETE FROM knowledge_nodes WHERE workspace_id = ?", workspaceId);
			jdbc.update("DELETE FROM tags WHERE workspace_id = ?", workspaceId);
			jdbc.update("DELETE FROM activity_logs WHERE workspace_id = ?", workspaceId);
			jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ?", workspaceId);
			jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId);
		}
		jdbc.update("DELETE FROM export_jobs WHERE requested_by = ?", userId);
		jdbc.update("DELETE FROM reauth_tokens WHERE token_family_id IN (SELECT id FROM auth_session_families WHERE user_id = ?)", userId);
		jdbc.update("DELETE FROM auth_sessions WHERE family_id IN (SELECT id FROM auth_session_families WHERE user_id = ?)", userId);
		jdbc.update("DELETE FROM auth_session_families WHERE user_id = ?", userId);
		jdbc.update("UPDATE security_audit_logs SET actor_user_id = NULL, workspace_id = NULL WHERE actor_user_id = ?", userId);
		jdbc.update("DELETE FROM users WHERE id = ?", userId);
		audit.record(null, null, "ACCOUNT_PURGED", "SUCCESS", null, Map.of("userIdHash", hash(userId)));
	}

	private static String hash(UUID userId) {
		return java.util.HexFormat.of().formatHex(TokenHasher.sha256(userId.toString())).substring(0, 12);
	}
}
