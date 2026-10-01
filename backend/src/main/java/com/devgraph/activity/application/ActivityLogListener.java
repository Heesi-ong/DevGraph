package com.devgraph.activity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.micrometer.core.instrument.MeterRegistry;


/**
 * 설계서 §15.2: 일반 activity_logs는 원 트랜잭션이 커밋된 뒤 기록하고, 기록 실패가 핵심 저장 성공을
 * 되돌리지 않는다. (보안 감사 로그는 성격이 달라 {@link SecurityAuditService}가 REQUIRES_NEW로 처리한다.)
 */
@Component
public class ActivityLogListener {

	private static final Logger log = LoggerFactory.getLogger(ActivityLogListener.class);

	private final ActivityLogWriter writer;
	private final MeterRegistry metrics;

	public ActivityLogListener(ActivityLogWriter writer, MeterRegistry metrics) {
		this.writer = writer;
		this.metrics = metrics;
	}

	// AFTER_COMMIT 시점에는 원 트랜잭션이 이미 끝났으므로 새 트랜잭션이 있어야 insert가 커밋된다.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onActivity(ActivityEvent event) {
		// 사용량 지표(Phase 8): 서버가 정한 닫힌 action 이름의 횟수만 센다. 제목·내용·사용자 식별자는 태그로 쓰지 않는다.
		metrics.counter("devgraph.activity", "action", event.action()).increment();
		try {
			writer.write(event);
		} catch (RuntimeException e) {
			log.warn("activity log 기록 실패(유실 허용): action={} object={}", event.action(), event.objectId(), e);
		}
	}
}
