package com.devgraph.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.DevGraphApplication;
import com.devgraph.auth.application.AdminAccountService;
import com.devgraph.support.AbstractIntegrationTest;

/** 설계서 §17.8 운영 CLI: 웹 서버 없는 컨텍스트(`--spring.main.web-application-type=none`)로 기동되고 계정을 초기화한다. */
class AdminCliContextTest extends AbstractIntegrationTest {

	@Autowired
	Environment env;
	@Autowired
	JdbcTemplate jdbc;

	@Test
	void contextStartsWithoutAWebServerAndForcesAPasswordReset() {
		String email = "cli-" + UUID.randomUUID() + "@example.com";
		HttpHeaders json = new HttpHeaders();
		json.setContentType(MediaType.APPLICATION_JSON);
		restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"),
				new HttpEntity<>(Map.of("email", email, "displayName", "Cli", "password", "original-password-1"), json), String.class);

		try (var cli = new SpringApplicationBuilder(DevGraphApplication.class).web(WebApplicationType.NONE).profiles("test")
				.run("--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
						"--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
						"--spring.datasource.password=" + env.getProperty("spring.datasource.password"), "--devgraph.jobs.enabled=false")) {
			String temporary = cli.getBean(AdminAccountService.class).forcePasswordReset(email);
			assertThat(temporary).hasSizeGreaterThanOrEqualTo(16);
			assertThat(jdbc.queryForObject("select must_change_password from users where email_normalized = ?", Boolean.class, email))
					.isTrue();
		}
	}
}
