package com.devgraph.activity.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.devgraph.activity.infrastructure.SecurityAuditLogJpaEntity;
import com.devgraph.activity.infrastructure.SecurityAuditLogRepository;

/**
 * 설계서 §15.2/§17.2.2: 보안 감사 이벤트는 원 트랜잭션과 별도의 REQUIRES_NEW로 즉시 커밋한다.
 * 로그인 실패처럼 원 트랜잭션이 rollback되는 사건에서도 감사 기록은 남아야 하기 때문이다.
 */
@Service
public class SecurityAuditService {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SecurityAuditLogRepository repository;
	private final JdbcTemplate jdbc;

	public SecurityAuditService(SecurityAuditLogRepository repository, JdbcTemplate jdbc) {
		this.repository = repository;
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(UUID actorUserId, String eventType, String outcome, String ipPrefix) {
		repository.save(new SecurityAuditLogJpaEntity(UUID.randomUUID(), actorUserId, eventType, outcome, ipPrefix));
	}

	/**
	 * 안전한 부가 정보(purpose, 대상 id, 이유 코드 등)를 함께 남긴다. **비밀번호·토큰 원문·본문은 넣지 않는다** —
	 * 호출하는 쪽이 문자열 값만 넘기고, 여기서는 JSON으로만 직렬화한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(UUID actorUserId, UUID workspaceId, String eventType, String outcome, String ipPrefix,
			Map<String, String> metadata) {
		jdbc.update("""
				INSERT INTO security_audit_logs (id, workspace_id, actor_user_id, event_type, ip_prefix, outcome, metadata)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""", UUID.randomUUID(), workspaceId, actorUserId, eventType, ipPrefix,
				outcome, JSON.writeValueAsString(metadata == null ? Map.of() : metadata));
	}
}
