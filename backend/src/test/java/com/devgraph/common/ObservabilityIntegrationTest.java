package com.devgraph.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 설계서 §21: 요청 로그는 필요한 필드만 남기고 비밀은 남기지 않는다. 프로브는 외부 정보를 노출하지 않는다. */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest extends AbstractIntegrationTest {

	@Test
	void requestLogHasTraceAndStatusButNeverSecrets(CapturedOutput output) {
		String email = "obs-" + UUID.randomUUID() + "@example.com";
		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		var signup = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"),
				new HttpEntity<>(Map.of("email", email, "displayName", "Obs", "password", "Sup3r-secret-pw-VALUE"), json), String.class);
		String token = JsonPath.read(signup.getBody(), "$.accessToken");
		HttpHeaders auth = new HttpHeaders();
		auth.setBearerAuth(token);

		var response = restTemplate.exchange(URI.create(baseUrl("/api/v1/search?q=my-private-query-term")), org.springframework.http.HttpMethod.GET,
				new HttpEntity<>(auth), String.class);
		String requestId = response.getHeaders().getFirst("X-Request-Id");
		assertThat(requestId).isNotBlank();
		var unauth = restTemplate.getForEntity(baseUrl("/api/v1/nodes"), String.class);
		assertThat(unauth.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(JsonPath.<String>read(unauth.getBody(), "$.traceId")).isEqualTo(unauth.getHeaders().getFirst("X-Request-Id"));

		String logs = output.getAll();
		assertThat(logs).contains("GET /api/v1/search -> 200").contains("GET /api/v1/nodes -> 401");
		assertThat(logs).doesNotContain("my-private-query-term").doesNotContain("Sup3r-secret-pw-VALUE").doesNotContain(token)
				.doesNotContain(email);
		// 같은 요청의 로그 줄에 traceId와 사용자 해시가 MDC로 붙는다(포맷은 프로파일이 정한다). 클라이언트가 보낸 값은 쓰지 않는다.
		HttpHeaders spoof = new HttpHeaders();
		spoof.add("X-Request-Id", "attacker-chosen");
		var spoofed = restTemplate.exchange(URI.create(baseUrl("/api/v1/nodes")), org.springframework.http.HttpMethod.GET, new HttpEntity<>(spoof), String.class);
		assertThat(spoofed.getHeaders().getFirst("X-Request-Id")).isNotEqualTo("attacker-chosen");
	}

	@Test
	void probesAreSplitAndExposeNoDetails() {
		var live = restTemplate.getForEntity(baseUrl("/actuator/health/liveness"), String.class);
		var ready = restTemplate.getForEntity(baseUrl("/actuator/health/readiness"), String.class);
		assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ready.getBody()).doesNotContain("jdbc").doesNotContain("postgres").doesNotContain("pending");
		// 내부 지표는 외부에 노출하지 않는다.
		assertThat(restTemplate.getForEntity(baseUrl("/actuator/metrics"), String.class).getStatusCode().is2xxSuccessful()).isFalse();
		assertThat(restTemplate.getForEntity(baseUrl("/actuator/env"), String.class).getStatusCode().is2xxSuccessful()).isFalse();
	}
}
