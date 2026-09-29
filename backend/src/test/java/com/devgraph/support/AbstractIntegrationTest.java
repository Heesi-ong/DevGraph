package com.devgraph.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * §20.1 Repository/Integration 공통 베이스: H2 대체 없이 실제 PostgreSQL을 쓴다.
 * 기본은 Testcontainers이며, Docker를 쓸 수 없는 환경에서는 -Ddevgraph.test.jdbc-url 로 이미 떠 있는
 * PostgreSQL(user/password는 devgraph.test.jdbc-user/-password, 기본 postgres)을 가리킬 수 있다.
 */
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

	private static final String EXTERNAL_URL = System.getProperty("devgraph.test.jdbc-url");
	private static PostgreSQLContainer<?> container;

	private static synchronized PostgreSQLContainer<?> container() {
		if (container == null) {
			container = new PostgreSQLContainer<>("postgres:16-alpine");
			container.start(); // JVM 종료 시 Ryuk이 정리한다.
		}
		return container;
	}

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		if (EXTERNAL_URL != null) {
			registry.add("spring.datasource.url", () -> EXTERNAL_URL);
			registry.add("spring.datasource.username", () -> System.getProperty("devgraph.test.jdbc-user", "postgres"));
			registry.add("spring.datasource.password",
					() -> System.getProperty("devgraph.test.jdbc-password", "postgres"));
			return;
		}
		registry.add("spring.datasource.url", () -> container().getJdbcUrl());
		registry.add("spring.datasource.username", () -> container().getUsername());
		registry.add("spring.datasource.password", () -> container().getPassword());
	}

	@LocalServerPort
	protected int port;

	@Autowired
	protected TestRestTemplate restTemplate;

	protected String baseUrl(String path) {
		return "http://localhost:" + port + path;
	}
}
