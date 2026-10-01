package com.devgraph.common.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 설계서 §21 요청당 한 줄 로그. method, path(query 제외), status, durationMs, traceId, userIdHash, errorCode만 남긴다.
 * 본문·헤더·쿠키·query는 비밀번호나 개인 지식을 담을 수 있으므로 기록하지 않는다. traceId는 항상 서버가 새로 만든다
 * (클라이언트가 보낸 값을 로그 키로 믿지 않는다). 보안 필터 체인보다 바깥에서 돌아 401/429도 같은 형식으로 남는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger("http.request");

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator/"); // 프로브가 로그를 채우지 않게
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		String traceId = UUID.randomUUID().toString();
		MDC.put("traceId", traceId);
		response.setHeader("X-Request-Id", traceId);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		} finally {
			long ms = (System.nanoTime() - start) / 1_000_000;
			MDC.put("status", Integer.toString(response.getStatus()));
			MDC.put("durationMs", Long.toString(ms));
			log.info("{} {} -> {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
			MDC.clear();
		}
	}
}
