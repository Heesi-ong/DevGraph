package com.devgraph.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.devgraph.support.AbstractIntegrationTest;

/**
 * 설계서 §27.2 "기존 database upgrade 검증": 이미 데이터가 있는 DB를 V10에서 최신으로 올린다. 깨끗한 DB의 전체 migration은
 * 다른 통합 테스트가 매번 검증한다. 여기서는 (1) 기존 데이터가 보존되고 (2) membership 제약이 새로 걸리며 (3) 고아 참조가
 * 있으면 조용히 지우지 않고 migration이 중단되는 것을 확인한다.
 */
class FlywayUpgradeTest extends AbstractIntegrationTest {

	@Autowired
	Environment env;

	private JdbcTemplate migrateTo(String schema, String target) {
		DataSource ds = new DriverManagerDataSource(env.getProperty("spring.datasource.url"),
				env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"));
		// SET search_path가 이어지도록 연결 하나를 계속 쓴다(풀/매번 새 연결이면 public에 쓰게 된다).
		JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(env.getProperty("spring.datasource.url"),
				env.getProperty("spring.datasource.username"), env.getProperty("spring.datasource.password"), true));
		if (target.equals("10")) {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); // 재실행해도 처음 상태에서 시작
		}
		jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
		// pg_trgm 연산자 클래스가 public에 있으므로 검색 경로에 public도 둔다(Flyway가 관리하는 schema는 하나).
		Flyway.configure().dataSource(ds).schemas(schema).initSql("SET search_path TO " + schema + ", public")
				.locations("classpath:db/migration").target(target).load().migrate();
		return jdbc;
	}

	private void seed(JdbcTemplate jdbc, String schema, boolean orphan) {
		UUID user = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		jdbc.execute("SET search_path TO " + schema + ", public");
		jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'U', 'x')",
				user, user + "@e.com", user + "@e.com");
		jdbc.update("INSERT INTO workspaces (id, name, slug) VALUES (?, 'W', ?)", workspace, "w-" + workspace);
		jdbc.update("INSERT INTO workspace_members (workspace_id, user_id) VALUES (?, ?)", workspace, user);
		jdbc.update("INSERT INTO knowledge_nodes (workspace_id, created_by, node_type, title) VALUES (?, ?, 'CONCEPT', 'kept')",
				workspace, user);
		if (orphan) {
			// V10까지는 막지 못하던 상태: 작성자가 그 Workspace의 member가 아니다.
			UUID stranger = UUID.randomUUID();
			jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'S', 'x')",
					stranger, stranger + "@e.com", stranger + "@e.com");
			jdbc.update("INSERT INTO knowledge_nodes (workspace_id, created_by, node_type, title) VALUES (?, ?, 'NOTE', 'orphan')",
					workspace, stranger);
		}
	}

	@Test
	void upgradingAnExistingDatabaseKeepsDataAndAddsMembershipConstraints() {
		String schema = "upgrade_ok";
		JdbcTemplate jdbc = migrateTo(schema, "10");
		seed(jdbc, schema, false);

		migrateTo(schema, "latest");
		jdbc.execute("SET search_path TO " + schema + ", public");
		assertThat(jdbc.queryForObject("select title from knowledge_nodes", String.class)).isEqualTo("kept");
		assertThat(jdbc.queryForObject("select count(*) from pg_constraint c join pg_namespace n on n.oid = c.connamespace "
				+ "where c.conname = 'fk_knowledge_nodes__workspace_member' and n.nspname = ?", Integer.class, schema)).isEqualTo(1);
		// 이제 member가 아닌 사용자가 만든 Node는 DB가 거부한다.
		UUID stranger = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, email, email_normalized, display_name, password_hash) VALUES (?, ?, ?, 'S', 'x')",
				stranger, stranger + "@e.com", stranger + "@e.com");
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO knowledge_nodes (workspace_id, created_by, node_type, title) SELECT id, ?, 'NOTE', 'bad' FROM workspaces", stranger))
				.hasMessageContaining("fk_knowledge_nodes__workspace_member");
	}

	@Test
	void upgradeStopsInsteadOfDeletingWhenOrphanReferencesExist() {
		String schema = "upgrade_orphan";
		JdbcTemplate jdbc = migrateTo(schema, "10");
		seed(jdbc, schema, true);

		assertThatThrownBy(() -> migrateTo(schema, "latest")).hasMessageContaining("membership preflight failed");
		jdbc.execute("SET search_path TO " + schema + ", public");
		assertThat(jdbc.queryForObject("select count(*) from knowledge_nodes", Integer.class)).isEqualTo(2); // 아무것도 지우지 않았다
	}
}
