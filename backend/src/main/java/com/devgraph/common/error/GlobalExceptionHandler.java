package com.devgraph.common.error;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 컨트롤러의 오류를 §14.1 ApiError envelope으로 통일한다.
 * ResponseEntityExceptionHandler를 상속해 404/405/415/JSON 파싱 오류 같은 프레임워크 예외도 500으로
 * 뭉개지 않고 같은 envelope의 올바른 status로 응답한다.
 * DB 제약 위반(SQLState/constraint 이름) 변환은 infrastructure 계층에서 ApiException으로 올린 뒤
 * 이 핸들러가 최종 직렬화만 담당한다(§15.2).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApiException(ApiException ex) {
		org.slf4j.MDC.put("errorCode", ex.getCode());
		ApiError body = ApiError.of(ex.getCode(), ex.getMessage(), ex.getFieldErrors(), newTraceId());
		var response = ResponseEntity.status(ex.getStatus());
		if (ex instanceof com.devgraph.common.ratelimit.RateLimitedException limited) {
			response.header("Retry-After", Long.toString(limited.getRetryAfterSeconds()));
		}
		return response.body(body);
	}

	/** JPA @Version 충돌(동시 수정). 설계서 §9.2 KNOW-03 / §14.3 409 VERSION_CONFLICT. */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
		org.slf4j.MDC.put("errorCode", "VERSION_CONFLICT");
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of("VERSION_CONFLICT",
				"다른 위치에서 수정되었습니다. 최신 내용을 확인해 주세요.", newTraceId()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
		org.slf4j.MDC.put("errorCode", "INTERNAL_ERROR");
		String traceId = newTraceId();
		// 응답에는 내부 상세를 숨기지만, 원인을 추적할 수 있도록 traceId와 함께 반드시 로그를 남긴다.
		log.error("Unhandled exception traceId={}", traceId, ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of("INTERNAL_ERROR", "일시적인 오류가 발생했습니다.", traceId));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ApiError.FieldError(fe.getField(), fe.getCode()))
				.toList();
		return ResponseEntity.badRequest()
				.body(ApiError.of("VALIDATION_FAILED", "입력값을 확인해 주세요.", fieldErrors, newTraceId()));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		ApiError error = ApiError.of(codeFor(status), messageFor(status), newTraceId());
		return ResponseEntity.status(statusCode).headers(headers).body(error);
	}

	private static String codeFor(@Nullable HttpStatus status) {
		if (status == null) {
			return "REQUEST_FAILED";
		}
		return switch (status) {
			case BAD_REQUEST -> "VALIDATION_FAILED";
			case NOT_FOUND -> "RESOURCE_NOT_FOUND";
			case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
			case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
			case PAYLOAD_TOO_LARGE -> "PAYLOAD_TOO_LARGE";
			default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_FAILED";
		};
	}

	private static String messageFor(@Nullable HttpStatus status) {
		if (status == null) {
			return "요청을 처리할 수 없습니다.";
		}
		return switch (status) {
			case BAD_REQUEST -> "요청 형식을 확인해 주세요.";
			case NOT_FOUND -> "요청한 리소스를 찾을 수 없습니다.";
			case METHOD_NOT_ALLOWED -> "허용되지 않는 요청 방식입니다.";
			case UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 요청 형식입니다.";
			case PAYLOAD_TOO_LARGE -> "요청이 너무 큽니다.";
			default -> "요청을 처리할 수 없습니다.";
		};
	}

	/** 요청 로그(RequestLoggingFilter)와 같은 traceId를 응답에도 써서 사용자가 알려 준 id로 로그를 바로 찾는다. */
	private static String newTraceId() {
		String current = org.slf4j.MDC.get("traceId");
		return current != null ? current : UUID.randomUUID().toString();
	}
}
