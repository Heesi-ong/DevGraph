package com.devgraph.common.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.devgraph.common.ratelimit.RateLimitFilter;
import com.devgraph.common.ratelimit.RateLimitProperties;
import com.devgraph.common.ratelimit.RateLimiter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 설계서 §17: JWT 인증 + double-submit CSRF. Spring Security의 세션/폼로그인은 쓰지 않는다 — 인증
 * 상태는 Access JWT(무상태)와 Refresh 쿠키(§17.2)로만 관리한다.
 */
// 웹 서버 없이 도는 운영 CLI(admin.command, §17.8)에서는 HttpSecurity가 없다. 서블릿 웹 앱에서만 보안 체인을 만든다.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Configuration
public class SecurityConfig {

	// style-src 'unsafe-inline'은 Monaco·React Flow의 inline style 때문이다. script-src는 완화하지 않는다(§17.7).
	private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
			+ "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";

	private final JwtTokenProvider tokenProvider;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties rateLimitProperties;

	@Value("${devgraph.cors.allowed-origins}")
	private String allowedOriginsCsv;

	public SecurityConfig(JwtTokenProvider tokenProvider, RateLimiter rateLimiter,
			RateLimitProperties rateLimitProperties) {
		this.tokenProvider = tokenProvider;
		this.rateLimiter = rateLimiter;
		this.rateLimitProperties = rateLimitProperties;
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
						// Export 다운로드는 브라우저가 링크로 여는 일회성 token URL이라 Bearer 없이 접근한다(토큰이 인증이다).
						.requestMatchers(HttpMethod.GET, "/api/v1/exports/*/download").permitAll()
						.requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
						.anyRequest().authenticated())
				// §14.1: 인증 실패는 빈 403이 아니라 표준 envelope의 401(AUTH_REQUIRED)이어야 한다.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint((request, response, ex) -> ApiErrorWriter.write(response,
								HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "인증이 필요합니다."))
						.accessDeniedHandler((request, response, ex) -> ApiErrorWriter.write(response,
								HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.")))
				.addFilterBefore(new CsrfDoubleSubmitFilter(allowedOrigins), UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(new JwtAuthenticationFilter(tokenProvider), UsernamePasswordAuthenticationFilter.class)
				// §17.2.3 제한 세션 → §17.6 사용량 제한 순서(JWT 해석 뒤에 와야 사용자를 알 수 있다).
				.addFilterAfter(new RestrictionFilter(), JwtAuthenticationFilter.class)
				.addFilterAfter(new RateLimitFilter(rateLimiter, rateLimitProperties), RestrictionFilter.class)
				// §17.7 응답 보안 헤더. HSTS는 HTTPS 요청에만 붙는다(로컬 개발의 http에서는 붙지 않는다).
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
						.httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
						.referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
						.frameOptions(frame -> frame.deny())
						.permissionsPolicyHeader(permissions -> permissions.policy("geolocation=(), camera=(), microphone=()")));
		return http.build();
	}

	/**
	 * 폼 로그인/Basic 인증을 쓰지 않으므로 UserDetailsService가 필요 없다. 빈을 하나 선언해 Spring Boot가
	 * "generated security password"를 자동 생성하는 기본 계정을 만들지 못하게 막는다.
	 */
	@Bean
	public UserDetailsService noUserDetailsService() {
		return username -> {
			throw new UsernameNotFoundException("form login is not used");
		};
	}

	private CorsConfigurationSource corsConfigurationSource(List<String> allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", AuthCookies.CSRF_HEADER, "X-Reauth-Token"));
		configuration.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
