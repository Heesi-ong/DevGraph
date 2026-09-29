package com.devgraph.common.web;

/**
 * 설계서 §12.3 `auth_session_families.device_label`: User-Agent를 로그인 시 한 번 "Chrome on macOS" 형태로
 * 정규화한다. 원문 User-Agent는 저장하지 않는다(과도한 fingerprinting 회피).
 */
public final class DeviceLabelParser {

	private DeviceLabelParser() {
	}

	public static String parse(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return null;
		}
		// 순서가 중요하다: Edge/Chrome UA는 "Safari/"를, Android UA는 "Linux"를, iPhone UA는 "Mac OS X"를 포함한다.
		String browser = userAgent.contains("Edg/") ? "Edge"
				: userAgent.contains("Firefox/") ? "Firefox"
				: userAgent.contains("Chrome/") ? "Chrome"
				: userAgent.contains("Safari/") ? "Safari"
				: "Browser";
		String os = userAgent.contains("Windows") ? "Windows"
				: userAgent.contains("Android") ? "Android"
				: (userAgent.contains("iPhone") || userAgent.contains("iPad")) ? "iOS"
				: userAgent.contains("Mac OS X") ? "macOS"
				: userAgent.contains("Linux") ? "Linux"
				: "Unknown OS";
		return browser + " on " + os;
	}
}
