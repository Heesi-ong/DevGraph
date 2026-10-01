package com.devgraph.activity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


/**
 * 설계서 §15.2: 일반 activity_logs는 원 트랜잭션이 커밋된 뒤 기록하고, 기록 실패가 핵심 저장 성공을
 * 되돌리지 않는다. (보안 감사 로그는 성격이 달라 {@link SecurityAuditService}가 REQUIRES_NEW로 처리한다.)
 */
@Component
public class ActivityLogListener {

	private static final Logger log = LoggerFactory.getLogger(ActivityLogListener.class);

	private final ActivityLogWriter writer;

	public ActivityLogListener(ActivityLogWriter writer) {
		this.writer = writer;
	}

	// AFTER_COMMIT 시점에는 원 트랜잭션이 이미 끝났으므로 새 트랜잭션이 있어야 insert가 커밋된다.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onActivity(ActivityEvent event) {
		try {
			writer.write(event);
		} catch (RuntimeException e) {
			log.warn("activity log 기록 실패(유실 허용): action={} object={}", event.action(), event.objectId(), e);
		}
	}
}
