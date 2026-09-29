package com.devgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.devgraph.support.AbstractIntegrationTest;

/** Phase 0 완료 조건: 빈 앱이 기동되고 health check를 통과한다(설계서 19절). */
class DevGraphApplicationTests extends AbstractIntegrationTest {

	@Test
	void contextLoads() {
	}

	@Test
	void healthEndpointReportsUp() {
		var response = restTemplate.getForEntity(baseUrl("/actuator/health"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"status\":\"UP\"");
	}
}
