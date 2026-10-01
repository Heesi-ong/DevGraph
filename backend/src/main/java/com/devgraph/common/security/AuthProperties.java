package com.devgraph.common.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** application.yml의 devgraph.auth.* 바인딩. 설계서 §17.2, §21.2. */
@Component
@ConfigurationProperties(prefix = "devgraph.auth")
public class AuthProperties {

	private Duration accessTokenTtl = Duration.ofMinutes(15);
	private Duration refreshTokenTtl = Duration.ofDays(30);
	private long sessionAbsoluteTtlDays = 90;
	// §17.2: 회전 직후 응답이 유실되어 브라우저가 직전 토큰을 다시 보내는 경우를 이 시간 동안만 허용한다.
	private Duration refreshGrace = Duration.ofSeconds(10);
	private String jwtSigningKey = "";

	public Duration getAccessTokenTtl() {
		return accessTokenTtl;
	}

	public void setAccessTokenTtl(Duration accessTokenTtl) {
		this.accessTokenTtl = accessTokenTtl;
	}

	public Duration getRefreshTokenTtl() {
		return refreshTokenTtl;
	}

	public void setRefreshTokenTtl(Duration refreshTokenTtl) {
		this.refreshTokenTtl = refreshTokenTtl;
	}

	public Duration getRefreshGrace() {
		return refreshGrace;
	}

	public void setRefreshGrace(Duration refreshGrace) {
		this.refreshGrace = refreshGrace;
	}

	public long getSessionAbsoluteTtlDays() {
		return sessionAbsoluteTtlDays;
	}

	public void setSessionAbsoluteTtlDays(long sessionAbsoluteTtlDays) {
		this.sessionAbsoluteTtlDays = sessionAbsoluteTtlDays;
	}

	public String getJwtSigningKey() {
		return jwtSigningKey;
	}

	public void setJwtSigningKey(String jwtSigningKey) {
		this.jwtSigningKey = jwtSigningKey;
	}
}
