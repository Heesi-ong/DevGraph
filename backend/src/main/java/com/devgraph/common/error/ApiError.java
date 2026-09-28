package com.devgraph.common.error;

import java.time.Instant;
import java.util.List;

/**
 * 설계서 §14.1의 표준 오류 응답 envelope.
 * 예: {"code":"VALIDATION_FAILED","message":"...","fieldErrors":[...],"traceId":"...","timestamp":"..."}
 */
public record ApiError(
		String code,
		String message,
		List<FieldError> fieldErrors,
		String traceId,
		Instant timestamp
) {

	public record FieldError(String field, String reason) {
	}

	public static ApiError of(String code, String message, String traceId) {
		return new ApiError(code, message, List.of(), traceId, Instant.now());
	}

	public static ApiError of(String code, String message, List<FieldError> fieldErrors, String traceId) {
		return new ApiError(code, message, fieldErrors, traceId, Instant.now());
	}
}
