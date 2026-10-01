package com.devgraph.common.security;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

/**
 * Spring MVC 바깥(Security filter chain)에서 §14.1 ApiError envelope을 그대로 내려보낸다.
 * 컨트롤러 밖에서 발생하는 401/403이 빈 본문으로 나가면 클라이언트가 오류 코드를 구분할 수 없다.
 */
public final class ApiErrorWriter {

	private ApiErrorWriter() {
	}

	public static void write(HttpServletResponse response, HttpStatus status, String code, String message)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write("{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message)
				+ "\",\"fieldErrors\":[],\"traceId\":\"" + traceId() + "\",\"timestamp\":\""
				+ Instant.now() + "\"}");
	}

	private static String traceId() {
		String current = org.slf4j.MDC.get("traceId");
		return current != null ? current : UUID.randomUUID().toString();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
