package com.devgraph.activity.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.infrastructure.SecurityAuditLogJpaEntity;
import com.devgraph.activity.infrastructure.SecurityAuditLogRepository;

/**
 * 설계서 §15.2/§17.2.2: 보안 감사 이벤트는 원 트랜잭션과 별도의 REQUIRES_NEW로 즉시 커밋한다.
 * 로그인 실패처럼 원 트랜잭션이 rollback되는 사건에서도 감사 기록은 남아야 하기 때문이다.
 */
@Service
public class SecurityAuditService {

	private final SecurityAuditLogRepository repository;

	public SecurityAuditService(SecurityAuditLogRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(UUID actorUserId, String eventType, String outcome, String ipPrefix) {
		repository.save(new SecurityAuditLogJpaEntity(UUID.randomUUID(), actorUserId, eventType, outcome, ipPrefix));
	}
}
