package com.devgraph.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 설계서 §17.2: Access JWT를 검증하고 {@link AuthenticatedUser}를 SecurityContext에 채운다. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenProvider tokenProvider;

	public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith("Bearer ")) {
			try {
				AuthenticatedUser user = tokenProvider.parse(header.substring("Bearer ".length()));
				var authentication = new UsernamePasswordAuthenticationToken(user, null, List.of());
				SecurityContextHolder.getContext().setAuthentication(authentication);
				// 요청 로그(RequestLoggingFilter)가 사용자 식별자를 원문 대신 해시 앞부분으로 남기게 한다.
				org.slf4j.MDC.put("userIdHash", java.util.HexFormat.of().formatHex(TokenHasher.sha256(user.userId().toString()), 0, 6));
			} catch (JwtTokenProvider.InvalidTokenException ignored) {
				// 인증 없이 통과시킨다 — 보호된 엔드포인트는 SecurityConfig의 authenticated() 규칙이 401로 막는다.
			}
		}
		filterChain.doFilter(request, response);
	}
}
