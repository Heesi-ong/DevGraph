package com.devgraph.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 0 최소 설정. 지금은 엔드포인트가 health뿐이라 전체를 permitAll로 둔다.
 * JWT 인증 필터, CSRF(double-submit cookie, §17.3), 세션 격리는 Phase 1에서 채운다.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
