package com.devgraph.ops;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.devgraph.account.application.AccountPurgeService;
import com.devgraph.knowledge.application.NodePurgeService;

/**
 * 설계서 §21.5 데이터 보존 정책표를 한 곳에서 실행한다. 기간은 이 표(설정)만 바꾸면 된다(다른 절의 숫자를 개별 수정하지 않는다).
 * 각 작업은 한 번에 처리하는 양을 제한해 긴 잠금을 피하고, 여러 번 돌려도 안전하다(멱등).
 */
@Service
@ConfigurationProperties(prefix = "devgraph.retention")
public class RetentionService {

	private int trashDays = 30;
	private int activityLogDays = 180;
	private int securityAuditDays = 365;
	/** 사용 완료·만료된 재인증 토큰은 이 시간이 지나면 지운다(§21.5: 5분 만료 후 정리). */
	private Duration reauthTokenGrace = Duration.ofMinutes(10);
	private int batchSize = 500;

	private final JdbcTemplate jdbc;
	private final NodePurgeService nodePurgeService;
	private final AccountPurgeService accountPurgeService;

	public RetentionService(JdbcTemplate jdbc, NodePurgeService nodePurgeService, AccountPurgeService accountPurgeService) {
		this.jdbc = jdbc;
		this.nodePurgeService = nodePurgeService;
		this.accountPurgeService = accountPurgeService;
	}

	/** 모든 보존 작업을 한 번씩 실행하고 작업별 삭제 건수를 돌려준다(운영 로그·테스트용). */
	public Map<String, Integer> runAll(Instant now) {
		Map<String, Integer> result = new LinkedHashMap<>();
		result.put("trashedNodes", nodePurgeService.purgeExpiredTrash(now, Duration.ofDays(trashDays), batchSize));
		result.put("activityLogs", jdbc.update(
				"DELETE FROM activity_logs WHERE id IN (SELECT id FROM activity_logs WHERE created_at < ? LIMIT ?)",
				Timestamp.from(now.minus(Duration.ofDays(activityLogDays))), batchSize));
		result.put("securityAuditLogs", jdbc.update(
				"DELETE FROM security_audit_logs WHERE id IN (SELECT id FROM security_audit_logs WHERE created_at < ? LIMIT ?)",
				Timestamp.from(now.minus(Duration.ofDays(securityAuditDays))), batchSize));
		result.put("reauthTokens", jdbc.update("""
				DELETE FROM reauth_tokens WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)""",
				Timestamp.from(now.minus(reauthTokenGrace)), Timestamp.from(now.minus(reauthTokenGrace))));
		result.put("accounts", accountPurgeService.purgeDue(now));
		return result;
	}

	public int getTrashDays() {
		return trashDays;
	}

	public void setTrashDays(int trashDays) {
		this.trashDays = trashDays;
	}

	public int getActivityLogDays() {
		return activityLogDays;
	}

	public void setActivityLogDays(int activityLogDays) {
		this.activityLogDays = activityLogDays;
	}

	public int getSecurityAuditDays() {
		return securityAuditDays;
	}

	public void setSecurityAuditDays(int securityAuditDays) {
		this.securityAuditDays = securityAuditDays;
	}

	public Duration getReauthTokenGrace() {
		return reauthTokenGrace;
	}

	public void setReauthTokenGrace(Duration reauthTokenGrace) {
		this.reauthTokenGrace = reauthTokenGrace;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}
}
