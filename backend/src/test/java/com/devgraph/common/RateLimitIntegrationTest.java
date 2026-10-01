package com.devgraph.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** 설계서 §17.6: 로그인 실패, 비밀번호 추측, 검색, 변경 요청의 한도를 낮춘 설정으로 실제 429를 확인한다. */
@TestPropertySource(properties = {
		"devgraph.rate-limit.login-failures-per-minute=3",
		"devgraph.rate-limit.password-attempt-failures-per-minute=3",
		"devgraph.rate-limit.search-per-minute=3",
		"devgraph.rate-limit.mutation-per-minute=5",
		"devgraph.rate-limit.window=PT60S" })
class RateLimitIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	@Test
	void loginFailuresAreLimitedPerIpAndEmailAndResetOnSuccess() {
		String email = email();
		signup(email);
		for (int i = 0; i < 3; i++) {
			assertThat(login(email, "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		// 한도에 닿으면 올바른 비밀번호도 거부한다(추측 방지). Retry-After가 있고 표준 오류 형식이다.
		var blocked = login(email, PASSWORD);
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(blocked.getBody()).contains("RATE_LIMITED");
		assertThat(Long.parseLong(blocked.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
		// 다른 이메일은 영향받지 않는다.
		String other = email();
		signup(other);
		assertThat(login(other, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
		// 존재하지 않는 계정도 같은 규칙이다(존재 여부를 응답 차이로 드러내지 않는다).
		String ghost = email();
		for (int i = 0; i < 3; i++) {
			assertThat(login(ghost, "x-x-x-x-x-x").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		assertThat(login(ghost, "x-x-x-x-x-x").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void successfulLoginClearsPreviousFailures() {
		String email = email();
		signup(email);
		login(email, "wrong-password");
		login(email, "wrong-password");
		assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK); // 성공이 카운트를 지운다.
		for (int i = 0; i < 3; i++) {
			assertThat(login(email, "wrong-password").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		assertThat(login(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void passwordGuessingThroughReauthAndPasswordChangeIsLimitedPerUser() {
		String token = signup(email());
		for (int i = 0; i < 3; i++) {
			assertThat(call(HttpMethod.POST, "/api/v1/auth/reauth", token,
					Map.of("password", "wrong-password", "purpose", "EXPORT_CREATE")).getStatusCode())
					.isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		var blocked = call(HttpMethod.POST, "/api/v1/auth/reauth", token, Map.of("password", PASSWORD, "purpose", "EXPORT_CREATE"));
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		String other = signup(email());
		for (int i = 0; i < 3; i++) {
			assertThat(call(HttpMethod.PATCH, "/api/v1/auth/password", other,
					Map.of("currentPassword", "wrong-password", "newPassword", "another-passw0rd")).getStatusCode())
					.isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		assertThat(call(HttpMethod.PATCH, "/api/v1/auth/password", other,
				Map.of("currentPassword", PASSWORD, "newPassword", "another-passw0rd")).getStatusCode())
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void mutationsAndSearchesAreLimitedPerUserWhileReadsAreNot() {
		String token = signup(email());
		String other = signup(email());
		for (int i = 0; i < 5; i++) {
			assertThat(call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "NOTE", "title", "n" + i)).getStatusCode())
					.isEqualTo(HttpStatus.CREATED);
		}
		var limited = call(HttpMethod.POST, "/api/v1/nodes", token, Map.of("type", "NOTE", "title", "one too many"));
		assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(limited.getBody()).contains("RATE_LIMITED");
		assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
		assertThat(limited.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff"); // 429에도 보안 헤더
		// 읽기는 제한하지 않는다. 막힌 쓰기가 데이터를 만들지 않았다.
		for (int i = 0; i < 10; i++) {
			assertThat(call(HttpMethod.GET, "/api/v1/nodes", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/nodes", token, null).getBody(), "$.items")).hasSize(5);
		// 다른 사용자는 영향받지 않는다.
		assertThat(call(HttpMethod.POST, "/api/v1/nodes", other, Map.of("type", "NOTE", "title", "mine")).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);

		for (int i = 0; i < 3; i++) {
			assertThat(call(HttpMethod.GET, "/api/v1/search?q=nothing", token, null).getStatusCode()).isEqualTo(HttpStatus.OK);
		}
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=nothing", token, null).getStatusCode())
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(call(HttpMethod.GET, "/api/v1/search?q=nothing", other, null).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ---- helpers ----

	private static String email() {
		return "rl-" + UUID.randomUUID() + "@example.com";
	}

	private ResponseEntity<String> login(String email, String password) {
		return post("/api/v1/auth/login", Map.of("email", email, "password", password));
	}

	private String signup(String email) {
		var response = post("/api/v1/auth/signup", Map.of("email", email, "displayName", "RL", "password", PASSWORD));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}

	private ResponseEntity<String> post(String path, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.postForEntity(baseUrl(path), new HttpEntity<>(body, headers), String.class);
	}

	private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(java.net.URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers), String.class);
	}
}
