package com.devgraph.common.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.devgraph.common.error.ApiException;

/**
 * 설계서 §14.7 opaque cursor. 정렬 기준 값들을 각각 base64url로 인코딩해 '.'로 이어 붙인다.
 * 값에 어떤 구분자가 들어 있어도 안전하고, 클라이언트는 내부 구조에 의존하지 않는다.
 */
public final class CursorCodec {

	private CursorCodec() {
	}

	public static String encode(String... parts) {
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				builder.append('.');
			}
			builder.append(encoder.encodeToString(parts[i].getBytes(StandardCharsets.UTF_8)));
		}
		return builder.toString();
	}

	/** 형식이 깨졌거나 기대한 개수와 다르면 400 INVALID_CURSOR. */
	public static List<String> decode(String cursor, int expectedParts) {
		try {
			String[] encoded = cursor.split("\\.", -1);
			if (encoded.length != expectedParts) {
				throw invalid();
			}
			Base64.Decoder decoder = Base64.getUrlDecoder();
			return java.util.Arrays.stream(encoded)
					.map(part -> new String(decoder.decode(part), StandardCharsets.UTF_8))
					.toList();
		} catch (IllegalArgumentException e) {
			throw invalid();
		}
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
	}
}
