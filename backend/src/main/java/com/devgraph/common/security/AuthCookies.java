package com.devgraph.common.security;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 설계서 §17.2/§17.3: Refresh 쿠키와 CSRF double-submit 쿠키를 일관된 속성으로 발급한다. */
@Component
public class AuthCookies {

	public static final String REFRESH_COOKIE = "refresh_token";
	public static final String CSRF_COOKIE = "csrf_token";
	public static final String CSRF_HEADER = "X-CSRF-Token";

	private final AuthProperties properties;
	private final boolean cookieSecure;

	public AuthCookies(AuthProperties properties, org.springframework.core.env.Environment env) {
		this.properties = properties;
		this.cookieSecure = env.getProperty("devgraph.cookie.secure", Boolean.class, true);
	}

	public ResponseCookie refreshCookie(String rawToken) {
		return ResponseCookie.from(REFRESH_COOKIE, rawToken)
				.httpOnly(true)
				.secure(cookieSecure)
				.sameSite("Lax")
				.path("/api/v1/auth")
				.maxAge(properties.getRefreshTokenTtl())
				.build();
	}

	public ResponseCookie csrfCookie(String value) {
		return ResponseCookie.from(CSRF_COOKIE, value)
				.httpOnly(false)
				.secure(cookieSecure)
				.sameSite("Lax")
				.path("/api/v1")
				.maxAge(properties.getRefreshTokenTtl())
				.build();
	}

	public ResponseCookie expiredRefreshCookie() {
		return ResponseCookie.from(REFRESH_COOKIE, "")
				.httpOnly(true)
				.secure(cookieSecure)
				.sameSite("Lax")
				.path("/api/v1/auth")
				.maxAge(0)
				.build();
	}
}
