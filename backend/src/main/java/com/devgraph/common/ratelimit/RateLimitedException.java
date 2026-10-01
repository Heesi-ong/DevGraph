package com.devgraph.common.ratelimit;

import org.springframework.http.HttpStatus;

import com.devgraph.common.error.ApiException;

/** `429 RATE_LIMITED`. `Retry-After`(초)는 응답 헤더로 내려간다(GlobalExceptionHandler, RateLimitFilter). */
public class RateLimitedException extends ApiException {

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}
