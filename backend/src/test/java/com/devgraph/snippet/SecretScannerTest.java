package com.devgraph.snippet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.devgraph.snippet.domain.SecretScanner;
import com.devgraph.snippet.domain.SecretScanner.Kind;

/** 설계서 §17.5 secret 감지: 잡아야 할 것, 넘어가야 할 것, 값이 결과에 새지 않는 것. */
class SecretScannerTest {

	@Test
	void detectsCommonSecretShapes() {
		assertThat(kinds("-----BEGIN RSA PRIVATE KEY-----")).containsExactly(Kind.PRIVATE_KEY);
		assertThat(kinds("aws = \"AKIAABCDEFGHIJKLMNOP\"")).contains(Kind.API_KEY);
		assertThat(kinds("token=" + "ghp_" + "a".repeat(36))).contains(Kind.API_KEY);
		assertThat(kinds("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk1234"))
				.contains(Kind.JWT);
		assertThat(kinds("jdbc.url=postgres://admin:s3cretpass@db.example.com:5432/app"))
				.contains(Kind.CONNECTION_STRING);
		assertThat(kinds("password = \"hunter2hunter2\"")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
		assertThat(kinds("{\"api_key\": \"abcd1234efgh5678\"}")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
		// 환경변수 이름 형태(접두어/접미어가 붙은 것)
		assertThat(kinds("export DB_PASSWORD=\"hunter2hunter2\"")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
		assertThat(kinds("API_TOKEN=abcd1234efgh5678")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
		assertThat(kinds("AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMIK7MDENG")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
		assertThat(kinds("clientSecret: \"s3cr3t-value-123\"")).containsExactly(Kind.CREDENTIAL_ASSIGNMENT);
	}

	@Test
	void ignoresPlaceholdersAndOrdinaryCode() {
		assertThat(SecretScanner.scan("password = ${DB_PASSWORD}")).isEmpty();
		assertThat(SecretScanner.scan("const token = process.env.API_TOKEN")).isEmpty();
		assertThat(SecretScanner.scan("password = getPassword()")).isEmpty();
		// 이름에 token/secret이 들어가도 자격 증명 대입이 아닌 경우
		assertThat(SecretScanner.scan("tokenizer = \"bert-base-uncased\"")).isEmpty();
		assertThat(SecretScanner.scan("token_expiry_seconds = 86400000")).isEmpty();
		assertThat(SecretScanner.scan("DB_PASSWORD=${DB_PASSWORD}")).isEmpty();
		assertThat(SecretScanner.scan("String password = request.getParameter(\"pw\");")).isEmpty();
		assertThat(SecretScanner.scan("url = \"postgres://${USER}:${PASS}@host/db\"")).isEmpty();
		assertThat(SecretScanner.scan("public class Hello { void run() { System.out.println(\"hi\"); } }")).isEmpty();
	}

	@Test
	void reportsLineNumbersAcrossLineEndingsAndNeverTheValue() {
		var findings = SecretScanner.scan("a\r\nb\nc\rsecret: \"topsecretvalue1\"");
		assertThat(findings).hasSize(1);
		assertThat(findings.get(0).line()).isEqualTo(4);
		assertThat(findings.toString()).doesNotContain("topsecretvalue1");
		assertThat(SecretScanner.hasHighRisk(SecretScanner.scan("-----BEGIN PRIVATE KEY-----"))).isTrue();
		assertThat(SecretScanner.hasHighRisk(findings)).isFalse();
	}

	@Test
	void scanCostStaysBoundedOnHugeSingleLines() {
		// 512KB 한 줄에서 정규식 백트래킹이 폭발하지 않아야 한다(요청 하나가 스레드를 붙잡는 문제).
		java.time.Duration limit = java.time.Duration.ofSeconds(5);
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(limit,
				() -> assertThat(SecretScanner.scan("a".repeat(512 * 1024))).isEmpty());
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(limit,
				() -> assertThat(SecretScanner.scan("eyJ".repeat(170_000))).isEmpty());
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(limit,
				() -> assertThat(SecretScanner.scan("a://".repeat(120_000))).isEmpty());
		// 긴 줄 한가운데(창 경계 근처 포함)의 secret도 여전히 잡는다.
		String padded = "x ".repeat(1000) + "password=\"hunter2hunter2\"" + " y".repeat(1000);
		assertThat(SecretScanner.scan(padded)).hasSize(1);
		for (int pad = 700; pad < 1100; pad += 37) {
			assertThat(SecretScanner.scan(" ".repeat(pad) + "-----BEGIN RSA PRIVATE KEY-----")).hasSize(1);
		}
	}

	private static java.util.List<Kind> kinds(String code) {
		return SecretScanner.scan(code).stream().map(SecretScanner.Finding::kind).toList();
	}
}
