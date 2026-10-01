package com.devgraph.ops;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정기 작업(보존 정책). `devgraph.jobs.enabled=false`면 등록되지 않는다(테스트는 서비스를 직접 호출한다).
 * 단일 인스턴스 가정이다. 인스턴스가 여러 개가 되면 각 작업에 advisory lock이 필요하다(§9.8과 같은 이유).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "devgraph.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledJobs {

	private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

	private final RetentionService retentionService;

	public ScheduledJobs(RetentionService retentionService) {
		this.retentionService = retentionService;
	}

	@Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
	public void retention() {
		try {
			Map<String, Integer> result = retentionService.runAll(Instant.now());
			log.info("retention job finished {}", result);
		} catch (RuntimeException e) {
			log.error("retention job failed", e);
		}
	}
}
