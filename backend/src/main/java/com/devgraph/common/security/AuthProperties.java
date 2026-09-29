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
