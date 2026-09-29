package com.devgraph.auth.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.devgraph.auth.infrastructure.AuthSessionFamilyJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.AuthSessionJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionRepository;
import com.devgraph.common.security.AuthProperties;
import com.devgraph.common.security.JwtTokenProvider;
import com.devgraph.common.security.TokenHasher;

/**
 * 설계서 §17.2.1: 로그인/가입 시 새 기기 세션(family)과 첫 rotation row를 함께 만든다.
 * Refresh/Login 양쪽에서 재사용하도록 분리했다(중복 로직 방지).
 */
@Service
public class SessionIssuanceService {

	private final AuthSessionFamilyRepository familyRepository;
	private final AuthSessionRepository sessionRepository;
	private final JwtTokenProvider tokenProvider;
	private final AuthProperties properties;

	public SessionIssuanceService(AuthSessionFamilyRepository familyRepository, AuthSessionRepository sessionRepository,
			JwtTokenProvider tokenProvider, AuthProperties properties) {
		this.familyRepository = familyRepository;
		this.sessionRepository = sessionRepository;
		this.tokenProvider = tokenProvider;
		this.properties = properties;
	}

	public IssuedTokens issueNewFamily(UUID userId, String deviceLabel, String ipPrefix) {
		Instant absoluteExpiresAt = Instant.now().plus(properties.getSessionAbsoluteTtlDays(), ChronoUnit.DAYS);
		AuthSessionFamilyJpaEntity family = new AuthSessionFamilyJpaEntity(
				UUID.randomUUID(), userId, deviceLabel, absoluteExpiresAt);
		familyRepository.save(family);
		return issueRotation(family, ipPrefix);
	}

	public IssuedTokens issueRotation(AuthSessionFamilyJpaEntity family, String ipPrefix) {
		String rawRefreshToken = TokenHasher.newOpaqueToken();
		Instant sessionExpiry = Instant.now().plus(properties.getRefreshTokenTtl());
		Instant expiresAt = sessionExpiry.isBefore(family.getAbsoluteExpiresAt())
				? sessionExpiry
				: family.getAbsoluteExpiresAt();
		AuthSessionJpaEntity session = new AuthSessionJpaEntity(
				UUID.randomUUID(), family.getId(), TokenHasher.sha256(rawRefreshToken), null, ipPrefix, expiresAt);
		sessionRepository.save(session);
		String accessToken = tokenProvider.issueAccessToken(family.getUserId(), family.getId(), "NONE");
		return new IssuedTokens(accessToken, rawRefreshToken, family.getId());
	}
}
