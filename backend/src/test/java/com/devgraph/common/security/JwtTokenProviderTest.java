package com.devgraph.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Docker 없이도 실행 가능한 순수 단위 테스트(§17.2 sid=token_family_id 계약 검증).
 * Testcontainers가 필요한 통합 테스트와 달리 Spring context를 띄우지 않는다.
 */
class JwtTokenProviderTest {

	private final AuthProperties properties = new AuthProperties();
	private final JwtTokenProvider provider;

	JwtTokenProviderTest() {
		properties.setJwtSigningKey("unit-test-signing-key");
		provider = new JwtTokenProvider(properties);
	}

	@Test
	void issuedTokenParsesBackToSameClaims() {
		UUID userId = UUID.randomUUID();
		UUID familyId = UUID.randomUUID();

		String token = provider.issueAccessToken(userId, familyId, "NONE");
		AuthenticatedUser parsed = provider.parse(token);

		assertThat(parsed.userId()).isEqualTo(userId);
		assertThat(parsed.familyId()).isEqualTo(familyId);
		assertThat(parsed.restriction()).isEqualTo("NONE");
	}

	@Test
	void tamperedTokenIsRejected() {
		String token = provider.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "NONE");
		// 서명 마지막 문자는 base64url 패딩 비트라 바꿔도 값이 같을 수 있다. 서명 중간 문자를 바꾼다.
		int i = token.length() - 10;
		String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);

		assertThatThrownBy(() -> provider.parse(tampered))
				.isInstanceOf(JwtTokenProvider.InvalidTokenException.class);
	}

	@Test
	void tokenSignedWithDifferentKeyIsRejected() {
		AuthProperties otherProperties = new AuthProperties();
		otherProperties.setJwtSigningKey("a-completely-different-signing-key");
		JwtTokenProvider otherProvider = new JwtTokenProvider(otherProperties);
		String token = otherProvider.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "NONE");

		assertThatThrownBy(() -> provider.parse(token))
				.isInstanceOf(JwtTokenProvider.InvalidTokenException.class);
	}
}
