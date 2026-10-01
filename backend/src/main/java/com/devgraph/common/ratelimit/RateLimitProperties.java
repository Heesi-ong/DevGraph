package com.devgraph.common.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 설계서 §17.6 Rate Limit 기본값. 모두 단일 인스턴스 in-memory 기준이다(다중 인스턴스는 공유 저장소 필요). */
@Component
@ConfigurationProperties(prefix = "devgraph.rate-limit")
public class RateLimitProperties {

	/** Login: IP + email hash 기준 실패 횟수(분당). 시도하기 전에 검사하고 실패할 때만 센다. */
	private int loginFailuresPerMinute = 5;
	/** 재인증·비밀번호 변경의 비밀번호 추측 방지: 사용자 기준 실패 횟수(분당). */
	private int passwordAttemptFailuresPerMinute = 5;
	private int searchPerMinute = 60;
	private int mutationPerMinute = 120;
	/** Export 생성 간격(분). 사용자당 이 간격에 1회. */
	private int exportIntervalMinutes = 10;
	private Duration window = Duration.ofMinutes(1);

	public int getLoginFailuresPerMinute() {
		return loginFailuresPerMinute;
	}

	public void setLoginFailuresPerMinute(int loginFailuresPerMinute) {
		this.loginFailuresPerMinute = loginFailuresPerMinute;
	}

	public int getPasswordAttemptFailuresPerMinute() {
		return passwordAttemptFailuresPerMinute;
	}

	public void setPasswordAttemptFailuresPerMinute(int passwordAttemptFailuresPerMinute) {
		this.passwordAttemptFailuresPerMinute = passwordAttemptFailuresPerMinute;
	}

	public int getSearchPerMinute() {
		return searchPerMinute;
	}

	public void setSearchPerMinute(int searchPerMinute) {
		this.searchPerMinute = searchPerMinute;
	}

	public int getMutationPerMinute() {
		return mutationPerMinute;
	}

	public void setMutationPerMinute(int mutationPerMinute) {
		this.mutationPerMinute = mutationPerMinute;
	}

	public int getExportIntervalMinutes() {
		return exportIntervalMinutes;
	}

	public void setExportIntervalMinutes(int exportIntervalMinutes) {
		this.exportIntervalMinutes = exportIntervalMinutes;
	}

	public Duration getWindow() {
		return window;
	}

	public void setWindow(Duration window) {
		this.window = window;
	}
}
