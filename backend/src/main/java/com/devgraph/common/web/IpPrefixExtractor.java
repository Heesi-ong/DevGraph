package com.devgraph.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** 설계서 §17.2: "IP 전체값 대신 보안 목적에 필요한 최소 정보만 보관" — IPv4는 /24로 마스킹한다. */
public final class IpPrefixExtractor {

	private IpPrefixExtractor() {
	}

	public static String from(HttpServletRequest request) {
		String remote = request.getRemoteAddr();
		if (remote == null) {
			return null;
		}
		String[] parts = remote.split("\\.");
		if (parts.length == 4) {
			return parts[0] + "." + parts[1] + "." + parts[2] + ".0/24";
		}
		return remote; // IPv6 등은 별도 마스킹 없이 그대로 둔다(초기 범위 밖).
	}
}
