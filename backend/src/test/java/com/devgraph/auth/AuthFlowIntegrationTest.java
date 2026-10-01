package com.devgraph.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.devgraph.support.AbstractIntegrationTest;

/**
 * 설계서 §19 Phase 1 완료 조건: 가입 시 Workspace 원자 생성, 새로고침 session 복구, logout 후 refresh
 * 불가, 보안 오류 표준화. §20.2: credential 오류, token rotation/reuse.
 */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void concurrentRefreshConsumesOnceAndRevokesTheFamilyOnReplay() throws Exception {
		var registered = signup(uniqueEmail(), "Concurrent", "correct-horse-battery");
		String old = cookie(registered, "refresh_token").orElseThrow();
		String csrf = cookie(registered, "csrf_token").orElseThrow();
		UUID family = jdbc.queryForObject("select family_id from auth_sessions where refresh_token_hash = ?",
				UUID.class, com.devgraph.common.security.TokenHasher.sha256(old));
		var pool = Executors.newFixedThreadPool(12);
		var ready = new CountDownLatch(12);
		var start = new CountDownLatch(1);
		List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < 12; i++) {
				futures.add(pool.submit(() -> { ready.countDown(); start.await(); return refresh(old, csrf); }));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<ResponseEntity<String>> responses = new ArrayList<>();
			for (var future : futures) responses.add(future.get(30, TimeUnit.SECONDS));
			assertThat(responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful())).hasSize(1);
			assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.UNAUTHORIZED)).hasSize(11);
			assertThat(responses.stream().anyMatch(r -> r.getBody().contains("TOKEN_REUSED"))).isTrue();
			assertThat(jdbc.queryForObject("select count(*) from auth_sessions where family_id = ?", Integer.class,
					family)).isEqualTo(2);
			assertThat(jdbc.queryForObject("select revoke_reason from auth_session_families where id = ?",
					String.class, family)).isEqualTo("REUSE_DETECTED");
			var winner = responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).findFirst().orElseThrow();
			assertThat(refresh(cookie(winner, "refresh_token").orElseThrow(), csrf).getStatusCode())
					.isEqualTo(HttpStatus.UNAUTHORIZED);
		} finally { start.countDown(); pool.shutdownNow(); }
	}

	@Test
	void sessionListUsesFamilyIdsLatestIpAndHidesExpiredSessions() {
		var registered = signup(uniqueEmail(), "Sessions", "correct-horse-battery");
		String token = extractAccessToken(registered.getBody());
		String old = cookie(registered, "refresh_token").orElseThrow();
		String csrf = cookie(registered, "csrf_token").orElseThrow();
		UUID family = jdbc.queryForObject("select family_id from auth_sessions where refresh_token_hash = ?",
				UUID.class, com.devgraph.common.security.TokenHasher.sha256(old));
		jdbc.update("update auth_sessions set ip_prefix = 'old' where family_id = ?", family);
		var rotated = refresh(old, csrf);
		jdbc.update("update auth_sessions set ip_prefix = 'latest' where refresh_token_hash = ?",
				com.devgraph.common.security.TokenHasher.sha256(cookie(rotated, "refresh_token").orElseThrow()));
		var headers = new HttpHeaders(); headers.setBearerAuth(token);
		var list = restTemplate.exchange(baseUrl("/api/v1/auth/sessions"), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);
		assertThat(JsonPath.<List<String>>read(list.getBody(), "$.items[*].id")).containsExactly(family.toString());
		assertThat(JsonPath.<String>read(list.getBody(), "$.items[0].lastIpPrefix")).isEqualTo("latest");
		jdbc.update("update auth_session_families set absolute_expires_at = now() - interval '1 second' where id = ?", family);
		assertThat(JsonPath.<List<?>>read(restTemplate.exchange(baseUrl("/api/v1/auth/sessions"), HttpMethod.GET,
				new HttpEntity<>(headers), String.class).getBody(), "$.items")).isEmpty();
	}

	@Test
	void signupCreatesUserAndReturnsTokens() {
		var response = signup(uniqueEmail(), "Ada Lovelace", "correct-horse-battery");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).contains("\"accessToken\"");
		assertThat(cookie(response, "refresh_token")).isPresent();
		assertThat(cookie(response, "csrf_token")).isPresent();
	}

	@Test
	void signupWithDuplicateEmailReturnsGenericConflict() {
		String email = uniqueEmail();
		signup(email, "First", "correct-horse-battery");

		var second = signup(email, "Second", "another-password");

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody()).contains("EMAIL_UNAVAILABLE");
	}

	@Test
	void loginWithWrongPasswordReturnsGenericInvalidCredentials() {
		String email = uniqueEmail();
		signup(email, "Grace Hopper", "correct-horse-battery");

		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/login"), jsonBody("""
				{"email":"%s","password":"wrong-password"}""".formatted(email)), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).contains("INVALID_CREDENTIALS");
	}

	@Test
	void meRequiresAuthentication() {
		var response = restTemplate.getForEntity(baseUrl("/api/v1/auth/me"), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void meReturnsUserAndWorkspaceWithAccessToken() {
		var signupResponse = signup(uniqueEmail(), "Margaret Hamilton", "correct-horse-battery");
		String accessToken = extractAccessToken(signupResponse.getBody());

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(accessToken);
		var response = restTemplate.exchange(baseUrl("/api/v1/auth/me"), HttpMethod.GET,
				new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("\"workspace\"");
	}

	@Test
	void refreshRotatesTokenAndOldCookieIsRejectedAfterReuse() {
		var signupResponse = signup(uniqueEmail(), "Katherine Johnson", "correct-horse-battery");
		String oldRefreshToken = cookie(signupResponse, "refresh_token").orElseThrow();
		String csrfToken = cookie(signupResponse, "csrf_token").orElseThrow();

		var firstRefresh = refresh(oldRefreshToken, csrfToken);
		assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);
		String newRefreshToken = cookie(firstRefresh, "refresh_token").orElseThrow();
		assertThat(newRefreshToken).isNotEqualTo(oldRefreshToken);

		// 정상 흐름: 새 토큰으로는 계속 refresh가 가능하다.
		var secondRefresh = refresh(newRefreshToken, csrfToken);
		assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);

		// 재사용 시도: 이미 rotate된 첫 번째 토큰을 다시 쓰면 거부되고 family 전체가 죽는다.
		var reuseAttempt = refresh(oldRefreshToken, csrfToken);
		assertThat(reuseAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(reuseAttempt.getBody()).contains("TOKEN_REUSED");

		// family가 죽었으므로 방금까지 유효했던 최신 토큰도 더 이상 쓸 수 없다.
		String latestRefreshToken = cookie(secondRefresh, "refresh_token").orElseThrow();
		var afterReuseRefresh = refresh(latestRefreshToken, csrfToken);
		assertThat(afterReuseRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void refreshWithoutCsrfHeaderIsRejected() {
		var signupResponse = signup(uniqueEmail(), "Radia Perlman", "correct-horse-battery");
		String refreshToken = cookie(signupResponse, "refresh_token").orElseThrow();

		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken);
		var response = restTemplate.exchange(baseUrl("/api/v1/auth/refresh"), HttpMethod.POST,
				new HttpEntity<>(headers), String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody()).contains("CSRF_FAILED");
	}

	@Test
	void logoutRevokesSessionSoSubsequentRefreshFails() {
		var signupResponse = signup(uniqueEmail(), "Hedy Lamarr", "correct-horse-battery");
		String accessToken = extractAccessToken(signupResponse.getBody());
		String refreshToken = cookie(signupResponse, "refresh_token").orElseThrow();
		String csrfToken = cookie(signupResponse, "csrf_token").orElseThrow();

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(accessToken);
		headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken + "; csrf_token=" + csrfToken);
		headers.add("X-CSRF-Token", csrfToken);
		var logoutResponse = restTemplate.exchange(baseUrl("/api/v1/auth/logout"), HttpMethod.POST,
				new HttpEntity<>(headers), Void.class);
		assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		var refreshAfterLogout = refresh(refreshToken, csrfToken);
		assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	private ResponseEntity<String> signup(String email, String displayName, String password) {
		return restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), jsonBody("""
				{"email":"%s","displayName":"%s","password":"%s"}""".formatted(email, displayName, password)),
				String.class);
	}

	private ResponseEntity<String> refresh(String refreshToken, String csrfToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.COOKIE, "refresh_token=" + refreshToken + "; csrf_token=" + csrfToken);
		headers.add("X-CSRF-Token", csrfToken);
		return restTemplate.exchange(baseUrl("/api/v1/auth/refresh"), HttpMethod.POST,
				new HttpEntity<>(headers), String.class);
	}

	private HttpEntity<String> jsonBody(String json) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
		return new HttpEntity<>(json, headers);
	}

	private String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private String extractAccessToken(String body) {
		int start = body.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private Optional<String> cookie(ResponseEntity<String> response, String name) {
		List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
		if (setCookies == null) {
			return Optional.empty();
		}
		return setCookies.stream()
				.filter(header -> header.startsWith(name + "="))
				.map(header -> header.substring((name + "=").length(), header.indexOf(';') < 0
						? header.length() : header.indexOf(';')))
				.findFirst();
	}
}
