package com.devgraph.common.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 설계서 §17: JWT 인증 + double-submit CSRF. Spring Security의 세션/폼로그인은 쓰지 않는다 — 인증
 * 상태는 Access JWT(무상태)와 Refresh 쿠키(§17.2)로만 관리한다.
 */
@Configuration
public class SecurityConfig {

	private final JwtTokenProvider tokenProvider;

	@Value("${devgraph.cors.allowed-origins}")
	private String allowedOriginsCsv;

	public SecurityConfig(JwtTokenProvider tokenProvider) {
		this.tokenProvider = tokenProvider;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		List<String> allowedOrigins = Arrays.asList(allowedOriginsCsv.split(","));
		http
				.csrf(AbstractHttpConfigurer::disable) // 우리 자체 double-submit 필터로 대체한다.
				.cors(cors -> cors.configurationSource(corsConfigurationSource(allowedOrigins)))
				.sessionManagement(session -> session.sessionCreationPolicy(
						org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login",
								"/api/v1/auth/refresh").permitAll()
						.requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(new CsrfDoubleSubmitFilter(allowedOrigins), UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(new JwtAuthenticationFilter(tokenProvider), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	private CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", AuthCookies.CSRF_HEADER));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
