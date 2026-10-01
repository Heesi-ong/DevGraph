package com.devgraph.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.devgraph.support.AbstractIntegrationTest;

/**
 * 설계서 NFR-03(OWASP 기본 점검): **등록된 모든** `/api/v1` 엔드포인트가 인증 없이는 거부되는지 전수 확인한다.
 * 새 컨트롤러를 추가하면서 인증을 빠뜨리면(공개 목록에 의도적으로 올리지 않는 한) 이 테스트가 실패한다.
 */
class ApiAuthSweepIntegrationTest extends AbstractIntegrationTest {

	/** 의도적으로 공개인 endpoint. 목록이 늘어나는 것은 보안 검토 대상이다. */
	private static final Set<String> PUBLIC = Set.of(
			"POST /api/v1/auth/signup", "POST /api/v1/auth/login", "POST /api/v1/auth/refresh",
			"GET /api/v1/exports/{jobId}/download");

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mapping;

	@Test
	void everyNonPublicEndpointRejectsRequestsWithoutAnAccessToken() {
		List<String> checked = new ArrayList<>();
		List<String> leaked = new ArrayList<>();
		for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (!pattern.startsWith("/api/v1/")) {
					continue;
				}
				for (var method : info.getMethodsCondition().getMethods()) {
					String route = method.name() + " " + pattern;
					if (PUBLIC.contains(route)) {
						continue;
					}
					String path = pattern.replaceAll("\\{versionNo\\}", "1").replaceAll("\\{[^}]+\\}", UUID.randomUUID().toString());
					HttpHeaders headers = new HttpHeaders();
					headers.setContentType(MediaType.APPLICATION_JSON);
					var response = restTemplate.exchange(java.net.URI.create(baseUrl(path)), HttpMethod.valueOf(method.name()),
							new HttpEntity<>("{}", headers), String.class);
					int status = response.getStatusCode().value();
					// 401(인증 필요). 로그아웃·refresh처럼 쿠키 기반 경로는 CSRF 403이 먼저일 수 있다. 어느 쪽이든 2xx/4xx(검증 오류) 여서는 안 된다.
					boolean rejected = status == 401 || (status == 403 && response.getBody() != null && response.getBody().contains("CSRF_FAILED"));
					checked.add(route + " -> " + status);
					if (!rejected) {
						leaked.add(route + " -> " + status);
					}
				}
			}
		}
		assertThat(checked).as("엔드포인트를 하나도 찾지 못했다").hasSizeGreaterThan(40);
		assertThat(leaked).as("인증 없이 접근 가능한 endpoint").isEmpty();
	}
}
