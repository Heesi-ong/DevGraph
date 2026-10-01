package com.devgraph.common.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.devgraph.common.security.ApiErrorWriter;
import com.devgraph.common.security.AuthenticatedUser;

/**
 * 설계서 §17.6: 인증된 사용자 기준으로 검색(분당 60)과 변경 요청(분당 120)을 제한한다. 로그인·재인증·Export는
 * 각 서비스가 더 구체적인 키로 직접 제한한다. 인증 전 요청은 건드리지 않는다(Spring Security가 401로 막는다).
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimiter limiter;
	private final RateLimitProperties properties;

	public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		String path = request.getRequestURI();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
				&& path.startsWith("/api/v1/")) {
			String method = request.getMethod();
			try {
				if (path.equals("/api/v1/search") && method.equals("GET")) {
					limiter.acquire("search:" + user.userId(), properties.getSearchPerMinute(), properties.getWindow());
				} else if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS")) {
					limiter.acquire("mutation:" + user.userId(), properties.getMutationPerMinute(), properties.getWindow());
				}
			} catch (RateLimitedException e) {
				response.setHeader("Retry-After", Long.toString(e.getRetryAfterSeconds()));
				MDC.put("errorCode", e.getCode());
				ApiErrorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, e.getCode(), e.getMessage());
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
