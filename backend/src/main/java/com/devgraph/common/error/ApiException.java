package com.devgraph.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인/애플리케이션 계층이 던지는 표준 API 오류. HTTP status와 §14.1 오류 코드를 함께 나른다.
 * infrastructure 계층의 DB 제약 위반 변환(§15.2)은 이 예외로 감싸서 올린다.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}
}
