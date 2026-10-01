package com.devgraph.activity.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.infrastructure.ActivityLogJpaEntity;
import com.devgraph.activity.infrastructure.ActivityLogRepository;

/** 호출자에게 반환하기 전에 flush와 commit이 끝나도록 독립 Bean의 트랜잭션 경계를 둔다. */
@Component
public class ActivityLogWriter {
	private final ActivityLogRepository repository;

	public ActivityLogWriter(ActivityLogRepository repository) {
		this.repository = repository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(ActivityEvent event) {
		repository.saveAndFlush(new ActivityLogJpaEntity(event.workspaceId(), event.actorUserId(), event.action(),
				event.objectType(), event.objectId()));
	}
}
