package com.devgraph.common.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 설계서 §17.2.3 계정 제한 상태. `restriction`이 NONE이 아닌 Access JWT는 화이트리스트 엔드포인트만 쓸 수 있고
 * 나머지는 `403 ACCOUNT_RESTRICTED`다. 비밀번호 강제 변경과 탈퇴 유예를 같은 메커니즘으로 처리한다.
 * Access JWT는 무상태라 제한이 걸리거나 풀린 직후에도 이미 발급된 토큰은 남은 TTL 동안 이전 상태를 유지한다 —
 * 프론트는 제한을 푸는 동작 직후 `/auth/refresh`로 새 토큰을 받는다.
 */
public class RestrictionFilter extends OncePerRequestFilter {

	static final String MUST_CHANGE_PASSWORD = "MUST_CHANGE_PASSWORD";
	static final String DELETION_PENDING = "DELETION_PENDING";

	private static final Set<String> COMMON = Set.of("POST /api/v1/auth/logout", "GET /api/v1/auth/me",
			// refresh는 쿠키로 인증하지만 브라우저 클라이언트가 만료 전 access token도 함께 보낸다. 제한을 푸는 동작 직후 새 토큰을
			// 받는 유일한 경로라 막으면 제한이 영영 풀리지 않는다. 새 토큰의 restriction은 서버가 매번 다시 계산한다.
			"POST /api/v1/auth/refresh");
	private static final Set<String> FOR_PASSWORD = Set.of("PATCH /api/v1/auth/password");
	private static final Set<String> FOR_DELETION = Set.of("POST /api/v1/account/deletion-cancel");

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
				&& user.restriction() != null && !"NONE".equals(user.restriction())) {
			String route = request.getMethod() + " " + request.getRequestURI();
			boolean allowed = COMMON.contains(route)
					|| (MUST_CHANGE_PASSWORD.equals(user.restriction()) && FOR_PASSWORD.contains(route))
					|| (DELETION_PENDING.equals(user.restriction()) && FOR_DELETION.contains(route));
			if (!allowed) {
				MDC.put("errorCode", "ACCOUNT_RESTRICTED");
				ApiErrorWriter.write(response, HttpStatus.FORBIDDEN, "ACCOUNT_RESTRICTED",
						MUST_CHANGE_PASSWORD.equals(user.restriction()) ? "비밀번호를 먼저 변경해 주세요."
								: "탈퇴 예약 상태입니다. 탈퇴를 취소하면 다시 사용할 수 있습니다.");
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
