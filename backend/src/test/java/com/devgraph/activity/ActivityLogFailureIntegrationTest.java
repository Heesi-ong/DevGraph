package com.devgraph.activity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class ActivityLogFailureIntegrationTest extends AbstractIntegrationTest {
	@Autowired JdbcTemplate jdbc;

	@Test
	void deferredAuditFailureDoesNotTurnCommittedNodeCreationIntoAnError() {
		// This is a test-only deferred trigger: it fires at commit, after saveAndFlush has succeeded.
		jdbc.execute("""
				CREATE FUNCTION test_reject_activity_commit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF EXISTS (SELECT 1 FROM knowledge_nodes WHERE id = NEW.object_id AND title = 'test-reject-log-commit') THEN
				    RAISE EXCEPTION 'test deferred activity failure';
				  END IF;
				  RETURN NEW;
				END $$
				""");
		jdbc.execute("""
				CREATE CONSTRAINT TRIGGER test_activity_commit_failure AFTER INSERT ON activity_logs
				DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_reject_activity_commit()
				""");
		try {
			var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
			String token = JsonPath.read(restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(Map.of(
					"email", UUID.randomUUID() + "@example.com", "displayName", "Audit", "password", "correct-horse-battery"), headers),
					String.class).getBody(), "$.accessToken");
			headers.setBearerAuth(token);
			var response = restTemplate.exchange(baseUrl("/api/v1/nodes"), HttpMethod.POST,
					new HttpEntity<>(Map.of("type", "CONCEPT", "title", "test-reject-log-commit"), headers), String.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			UUID id = UUID.fromString(JsonPath.read(response.getBody(), "$.id"));
			assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes where id = ?", Integer.class, id)).isEqualTo(1);
			assertThat(jdbc.queryForObject("select count(*) from activity_logs where object_id = ?", Integer.class, id)).isZero();
		} finally {
			jdbc.execute("DROP TRIGGER test_activity_commit_failure ON activity_logs");
			jdbc.execute("DROP FUNCTION test_reject_activity_commit()");
		}
	}
}
