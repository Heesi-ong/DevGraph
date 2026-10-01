package com.devgraph.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class WorkspaceMembershipIntegrationTest extends AbstractIntegrationTest {
	@Autowired JdbcTemplate jdbc;

	@Test
	void allUserReferencesRejectNonMembers() {
		String owner = signup();
		String other = signup();
		var headers = new HttpHeaders(); headers.setBearerAuth(owner); headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.exchange(baseUrl("/api/v1/snippets"), HttpMethod.POST,
				new HttpEntity<>(Map.of("title", "Owned", "language", "java", "code", "class Owned {}"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID node = UUID.fromString(JsonPath.read(response.getBody(), "$.id"));
		UUID ws = jdbc.queryForObject("select workspace_id from knowledge_nodes where id = ?", UUID.class, node);
		var otherHeaders = new HttpHeaders(); otherHeaders.setBearerAuth(other);
		UUID otherUser = UUID.fromString(JsonPath.read(restTemplate.exchange(baseUrl("/api/v1/auth/me"), HttpMethod.GET,
				new HttpEntity<>(otherHeaders), String.class).getBody(), "$.user.id"));
		assertRejected("update knowledge_nodes set created_by = ? where id = ?", "fk_knowledge_nodes__workspace_member", otherUser, node);
		assertRejected("update snippet_versions set created_by = ? where snippet_node_id = ?", "fk_snippet_versions__workspace_member", otherUser, node);
		assertRejected("insert into favorites(workspace_id, user_id, node_id) values (?, ?, ?)",
				"fk_favorites__workspace_member", ws, otherUser, node);
		assertRejected("insert into node_views(workspace_id, user_id, node_id) values (?, ?, ?)",
				"fk_node_views__workspace_member", ws, otherUser, node);
		assertRejected("insert into activity_logs(workspace_id, actor_user_id, action, object_type, object_id) values (?, ?, 'TEST', 'NODE', ?)",
				"fk_activity_logs__workspace_member", ws, otherUser, node);
		UUID target = jdbc.queryForObject("""
				insert into knowledge_nodes(workspace_id, created_by, node_type, title)
				select workspace_id, created_by, 'CONCEPT', 'Target' from knowledge_nodes where id = ? returning id
				""", UUID.class, node);
		assertRejected("""
				insert into knowledge_relations(workspace_id, created_by, source_node_id, target_node_id, relation_type_id)
				values (?, ?, ?, ?, (select id from relation_types where key = 'RELATED_TO'))
				""", "fk_knowledge_relations__workspace_member", ws, otherUser, node, target);
	}

	private void assertRejected(String sql, String constraint, Object... args) {
		assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining(constraint);
	}

	private String signup() {
		var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"), new HttpEntity<>(Map.of(
				"email", UUID.randomUUID() + "@example.com", "displayName", "Member", "password", "correct-horse-battery"), headers), String.class);
		return JsonPath.read(response.getBody(), "$.accessToken");
	}
}
