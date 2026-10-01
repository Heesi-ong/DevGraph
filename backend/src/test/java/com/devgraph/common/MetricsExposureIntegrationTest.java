package com.devgraph.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** docs/PHASE8_PLAN.md §1: 지표는 분리된 management 포트에서만 보이고, 개인 지식 내용은 담지 않는다. */
@TestPropertySource(properties = { "management.server.port=0",
		"management.endpoints.web.exposure.include=health,info,metrics,prometheus",
		// @SpringBootTest는 지표 exporter를 기본으로 끈다.
		"management.prometheus.metrics.export.enabled=true" })
class MetricsExposureIntegrationTest extends AbstractIntegrationTest {

	@LocalManagementPort
	int managementPort;

	@Test
	void metricsAreOnlyReachableOnTheManagementPortAndCarryNoPersonalContent() {
		String secretTitle = "PRIVATE-TITLE-" + UUID.randomUUID();
		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		var signup = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(
				Map.of("email", "m-" + UUID.randomUUID() + "@example.com", "displayName", "Metric", "password", "correct-horse-battery"), json),
				String.class);
		HttpHeaders auth = new HttpHeaders();
		auth.setBearerAuth(JsonPath.read(signup.getBody(), "$.accessToken"));
		auth.setContentType(MediaType.APPLICATION_JSON);
		restTemplate.postForEntity(baseUrl("/api/v1/nodes"), new HttpEntity<>(Map.of("type", "CONCEPT", "title", secretTitle), auth), String.class);

		String mgmt = "http://localhost:" + managementPort;
		var prometheus = restTemplate.getForEntity(mgmt + "/actuator/prometheus", String.class);
		assertThat(prometheus.getStatusCode().is2xxSuccessful()).as("prometheus " + prometheus.getStatusCode() + " " + prometheus.getBody()).isTrue();
		assertThat(prometheus.getBody()).contains("devgraph_activity_total").contains("action=\"NODE_CREATED\"");
		assertThat(prometheus.getBody()).doesNotContain(secretTitle).doesNotContain("userId").doesNotContain("@example.com");
		assertThat(restTemplate.getForEntity(mgmt + "/actuator/health/readiness", String.class).getStatusCode().is2xxSuccessful()).isTrue();

		// main 포트에서는 지표가 열리지 않는다(분리됐고, 보안 규칙도 막는다).
		assertThat(restTemplate.getForEntity(baseUrl("/actuator/prometheus"), String.class).getStatusCode().is2xxSuccessful()).isFalse();
		assertThat(restTemplate.getForEntity(baseUrl("/actuator/metrics"), String.class).getStatusCode().is2xxSuccessful()).isFalse();
	}
}
