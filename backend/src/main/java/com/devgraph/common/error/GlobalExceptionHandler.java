package com.devgraph.common.error;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 컨트롤러의 오류를 §14.1 ApiError envelope으로 통일한다.
 * DB 제약 위반(SQLState/constraint 이름) 변환은 infrastructure 계층에서 ApiException으로 올린 뒤
 * 이 핸들러가 최종 직렬화만 담당한다(§15.2).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApiException(ApiException ex) {
		String traceId = newTraceId();
		return ResponseEntity.status(ex.getStatus()).body(ApiError.of(ex.getCode(), ex.getMessage(), traceId));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
		String traceId = newTraceId();
		List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ApiError.FieldError(fe.getField(), fe.getCode()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.of("VALIDATION_FAILED", "입력값을 확인해 주세요.", fieldErrors, traceId));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		String traceId = newTraceId();
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of("INTERNAL_ERROR", "일시적인 오류가 발생했습니다.", traceId));
	}

	private String newTraceId() {
		return UUID.randomUUID().toString();
	}
}
