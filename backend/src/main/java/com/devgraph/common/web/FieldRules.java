package com.devgraph.common.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatus;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;

/**
 * 입력 필드 검증의 공통 규칙(§21.6, §14.1). 검증 실패는 `400 VALIDATION_FAILED` + fieldErrors, 크기 초과는
 * 입력을 조용히 자르지 않고 `413 PAYLOAD_TOO_LARGE`다. PostgreSQL이 저장하지 못하는 NUL은 500이 되기 전에 거부한다.
 */
public final class FieldRules {

	private FieldRules() {
	}

	/** 비어 있으면 안 되는 텍스트. 앞뒤 공백은 제거하고 내용은 그대로 둔다. */
	public static String required(String field, String value, int maxBytes) {
		String text = value == null ? "" : value.trim();
		if (text.isEmpty()) {
			throw validation(field, "REQUIRED");
		}
		return checked(field, text, maxBytes);
	}

	/** 선택 텍스트. null이면 null, 공백뿐이면 null(지움). */
	public static String optional(String field, String value, int maxBytes) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return checked(field, value.trim(), maxBytes);
	}

	private static String checked(String field, String text, int maxBytes) {
		if (text.indexOf('\u0000') >= 0) {
			throw validation(field, "INVALID_CHARACTER");
		}
		if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "입력이 너무 큽니다.",
					List.of(new ApiError.FieldError(field, "MAX_" + maxBytes + "_BYTES")));
		}
		return text;
	}

	public static ApiException validation(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
				List.of(new ApiError.FieldError(field, reason)));
	}

	/** http/https 절대 URL만 허용한다(§17.4). 계정 정보(user:pass@)가 든 URL은 비밀이 저장되므로 거부한다. */
	public static String httpUrl(String field, String value, int maxChars) {
		String url = value == null ? "" : value.trim();
		if (url.isEmpty()) {
			throw validation(field, "REQUIRED");
		}
		if (url.length() > maxChars) {
			throw validation(field, "MAX_" + maxChars);
		}
		try {
			java.net.URI uri = new java.net.URI(url);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
			boolean ok = (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
					&& uri.getUserInfo() == null;
			if (!ok) {
				throw validation(field, "INVALID_URL");
			}
		} catch (java.net.URISyntaxException e) {
			throw validation(field, "INVALID_URL");
		}
		return url;
	}
}
