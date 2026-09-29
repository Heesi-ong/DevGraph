package com.devgraph.common.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 설계서 §17.3: cookie 인증을 사용하는 mutation(refresh, logout, 계정 탈퇴)만 Origin allowlist +
 * double-submit cookie로 검증한다. Authorization 헤더로 인증하는 나머지 엔드포인트는 CSRF 대상이
 * 아니다 — 공격 페이지가 그 헤더를 대신 실어 보낼 수 없기 때문이다.
 */
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

	private static final Set<String> PROTECTED_PATHS = Set.of(
			"/api/v1/auth/refresh",
			"/api/v1/auth/logout"
	);

	private final List<String> allowedOrigins;

	public CsrfDoubleSubmitFilter(List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {
		if (PROTECTED_PATHS.contains(request.getRequestURI())) {
			String origin = request.getHeader("Origin");
			String header = request.getHeader(AuthCookies.CSRF_HEADER);
			String cookieValue = readCookie(request, AuthCookies.CSRF_COOKIE);
			boolean originOk = origin == null || allowedOrigins.contains(origin);
			boolean tokenOk = header != null && cookieValue != null && header.equals(cookieValue);
			if (!originOk || !tokenOk) {
				ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, "CSRF_FAILED", "CSRF 검증에 실패했습니다.");
				return;
			}
		}
		filterChain.doFilter(request, response);
	}

	private String readCookie(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (cookie.getName().equals(name)) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
