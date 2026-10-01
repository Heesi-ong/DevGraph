package com.devgraph.export.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 설계서 §21.2 `EXPORT_TEMP_DIR`, `MAX_EXPORT_SIZE_MB`와 §9.8의 만료·재시도 값. */
@Component
@ConfigurationProperties(prefix = "devgraph.export")
public class ExportProperties {

	private String tempDir = "/tmp/devgraph-export";
	private long maxSizeMb = 250;
	/** 다운로드 token 유효 시간(§9.8: 15분). */
	private Duration downloadTokenTtl = Duration.ofMinutes(15);
	/** 완료된 파일의 보관 시간(§9.8: 다운로드 완료 또는 만료 후 1시간 이내 삭제). */
	private Duration fileRetention = Duration.ofHours(1);
	/** 자동 재시도 횟수(§9.8: 최대 2회). 최초 시도 포함 총 3번까지 처리한다. */
	private int maxRetries = 2;
	/** PROCESSING에서 이 시간 넘게 멈춘 job은 프로세스가 죽은 것으로 보고 다시 대기열로 돌린다. */
	private Duration stuckAfter = Duration.ofMinutes(15);

	public String getTempDir() {
		return tempDir;
	}

	public void setTempDir(String tempDir) {
		this.tempDir = tempDir;
	}

	public long getMaxSizeMb() {
		return maxSizeMb;
	}

	public void setMaxSizeMb(long maxSizeMb) {
		this.maxSizeMb = maxSizeMb;
	}

	public Duration getDownloadTokenTtl() {
		return downloadTokenTtl;
	}

	public void setDownloadTokenTtl(Duration downloadTokenTtl) {
		this.downloadTokenTtl = downloadTokenTtl;
	}

	public Duration getFileRetention() {
		return fileRetention;
	}

	public void setFileRetention(Duration fileRetention) {
		this.fileRetention = fileRetention;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getStuckAfter() {
		return stuckAfter;
	}

	public void setStuckAfter(Duration stuckAfter) {
		this.stuckAfter = stuckAfter;
	}
}
