package com.devgraph.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Refresh/Reauth 토큰처럼 "DB에는 hash만 저장"해야 하는 opaque 토큰을 위한 유틸(설계서 §17.2, §17.2.2).
 * 원문은 클라이언트에만 전달하고 서버는 SHA-256 다이제스트만 보관한다.
 */
public final class TokenHasher {

	private static final SecureRandom RANDOM = new SecureRandom();

	private TokenHasher() {
	}

	public static String newOpaqueToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
