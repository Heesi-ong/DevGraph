package com.devgraph.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

/**
 * 설계서 §17.2: Access JWT 발급/검증. claim은 sub(userId), sid(family id), restriction만 담는다.
 * sid는 rotation row가 아니라 {@code auth_session_families.id}를 가리킨다(§17.2.1) — rotation이
 * 일어나도 같은 JWT 세션 식별자가 유지되어야 하기 때문이다.
 */
@Component
public class JwtTokenProvider {

	private static final String CLAIM_SID = "sid";
	private static final String CLAIM_RESTRICTION = "restriction";

	private final SecretKey key;
	private final AuthProperties properties;

	public JwtTokenProvider(AuthProperties properties) {
		this.properties = properties;
		this.key = Keys.hmacShaKeyFor(deriveKeyBytes(properties.getJwtSigningKey()));
	}

	private static byte[] deriveKeyBytes(String signingKey) {
		if (signingKey == null || signingKey.isBlank()) {
			throw new IllegalStateException("JWT_SIGNING_KEY가 설정되지 않았습니다.");
		}
		try {
			// 원본 문자열 길이와 무관하게 HMAC-SHA256에 필요한 256bit 키를 보장한다.
			return MessageDigest.getInstance("SHA-256").digest(signingKey.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public String issueAccessToken(UUID userId, UUID familyId, String restriction) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + properties.getAccessTokenTtl().toMillis());
		return Jwts.builder()
				.subject(userId.toString())
				.claim(CLAIM_SID, familyId.toString())
				.claim(CLAIM_RESTRICTION, restriction)
				.issuedAt(now)
				.expiration(expiry)
				.signWith(key)
				.compact();
	}

	public long accessTokenTtlSeconds() {
		return TimeUnit.MILLISECONDS.toSeconds(properties.getAccessTokenTtl().toMillis());
	}

	public AuthenticatedUser parse(String token) {
		try {
			var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
			return new AuthenticatedUser(
					UUID.fromString(claims.getSubject()),
					UUID.fromString(claims.get(CLAIM_SID, String.class)),
					claims.get(CLAIM_RESTRICTION, String.class));
		} catch (JwtException | IllegalArgumentException e) {
			throw new InvalidTokenException("유효하지 않은 토큰입니다.", e);
		}
	}

	public static class InvalidTokenException extends RuntimeException {
		public InvalidTokenException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
