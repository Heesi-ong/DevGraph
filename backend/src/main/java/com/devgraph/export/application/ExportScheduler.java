package com.devgraph.export.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 설계서 §9.8 폴러: 수 초 간격으로 대기 중인 job을 처리하고, 몇 분마다 만료 파일을 정리한다. */
@Component
@ConditionalOnProperty(name = "devgraph.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ExportScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExportScheduler.class);

	private final ExportProcessor processor;

	public ExportScheduler(ExportProcessor processor) {
		this.processor = processor;
	}

	@Scheduled(initialDelayString = "PT5S", fixedDelayString = "${devgraph.export.poll-interval:PT3S}")
	public void poll() {
		try {
			while (processor.processNext()) {
				// 대기 중인 job을 모두 처리한다.
			}
		} catch (RuntimeException e) {
			log.error("export poller failed", e);
		}
	}

	@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
	public void cleanup() {
		try {
			int cleaned = processor.cleanup(Instant.now());
			if (cleaned > 0) {
				log.info("export cleanup finished cleaned={}", cleaned);
			}
		} catch (RuntimeException e) {
			log.error("export cleanup failed", e);
		}
	}
}
