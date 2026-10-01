package com.devgraph.resource.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 설계서 §12.3 `resources.url_normalized` 계산 규칙(중복 URL 경고의 판단 기준).
 * <ol>
 * <li>scheme을 소문자로 통일한다.</li>
 * <li>host를 소문자로 통일하고 기본 포트(http 80, https 443)는 제거한다.</li>
 * <li>path 끝의 trailing slash를 제거한다(루트 `/`는 유지하고, 빈 path도 `/`로 본다).</li>
 * <li>fragment는 제거한다.</li>
 * <li>query parameter는 key 오름차순으로 정렬해 유지한다. 추적 파라미터(`utm_*`, `ref`, `fbclid`, `gclid`)는 제거한다.</li>
 * </ol>
 * path와 query 값은 디코딩하지 않는다 — 인코딩을 바꾸면 서로 다른 주소가 같아질 수 있다.
 */
public final class UrlNormalizer {

	private UrlNormalizer() {
	}

	/** 호출자가 http/https 절대 URL임을 이미 검증했다고 가정한다. 해석할 수 없으면 IllegalArgumentException. */
	public static String normalize(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("invalid url", e);
		}
		if (uri.getScheme() == null || uri.getHost() == null) {
			throw new IllegalArgumentException("not an absolute url");
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);

		String path = uri.getRawPath() == null ? "" : uri.getRawPath();
		while (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		if (path.isEmpty()) {
			path = "/";
		}

		StringBuilder out = new StringBuilder(scheme).append("://").append(host);
		if (!defaultPort) {
			out.append(':').append(port);
		}
		out.append(path);
		String query = normalizeQuery(uri.getRawQuery());
		if (!query.isEmpty()) {
			out.append('?').append(query);
		}
		return out.toString();
	}

	private static String normalizeQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return "";
		}
		List<String> params = new ArrayList<>();
		for (String param : rawQuery.split("&")) {
			if (param.isEmpty()) {
				continue;
			}
			String key = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
			if (!isTracking(key.toLowerCase(Locale.ROOT))) {
				params.add(param);
			}
		}
		params.sort(Comparator.comparing((String p) -> p.contains("=") ? p.substring(0, p.indexOf('=')) : p)
				.thenComparing(Comparator.naturalOrder()));
		return String.join("&", params);
	}

	private static boolean isTracking(String key) {
		return key.startsWith("utm_") || key.equals("ref") || key.equals("fbclid") || key.equals("gclid");
	}
}
