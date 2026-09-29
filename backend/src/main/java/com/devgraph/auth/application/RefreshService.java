package com.devgraph.auth.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.AuthSessionJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.security.TokenHasher;

/**
 * 설계서 §17.2/§9.1 AUTH-03. Refresh rotation과 재사용 감지.
 * "이미 rotate/revoke된 토큰의 재제출"(탈취 의심)과 "그냥 시간이 지나 만료됨"(정상적인 세션 종료)을
 * 구분한다 — 후자까지 REUSE_DETECTED로 몰면 오랜만에 접속한 정상 사용자를 공격자로 오분류하게 된다.
 */
@Service
public class RefreshService {

	private final AuthSessionRepository sessionRepository;
	private final AuthSessionFamilyRepository familyRepository;
	private final SessionIssuanceService sessionIssuanceService;
	private final SecurityAuditService securityAuditService;

	public RefreshService(AuthSessionRepository sessionRepository, AuthSessionFamilyRepository familyRepository,
			SessionIssuanceService sessionIssuanceService, SecurityAuditService securityAuditService) {
		this.sessionRepository = sessionRepository;
		this.familyRepository = familyRepository;
		this.sessionIssuanceService = sessionIssuanceService;
		this.securityAuditService = securityAuditService;
	}

	// noRollbackFor: 재사용 감지/절대 만료에서는 family를 폐기(변경)한 뒤 ApiException을 던진다. 기본 설정이면
	// 예외 때문에 트랜잭션이 롤백되어 폐기가 취소되고, 탈취된 family가 계속 살아남는다.
	@Transactional(noRollbackFor = ApiException.class)
	public IssuedTokens refresh(String rawRefreshToken, String ipPrefix) {
		byte[] hash = TokenHasher.sha256(rawRefreshToken);
		AuthSessionJpaEntity session = sessionRepository.findByRefreshTokenHash(hash).orElse(null);
		if (session == null) {
			throw fail(null, "TOKEN_EXPIRED", ipPrefix);
		}

		AuthSessionFamilyJpaEntity family = familyRepository.findById(session.getFamilyId()).orElse(null);
		if (family == null) {
			throw fail(null, "TOKEN_EXPIRED", ipPrefix);
		}
		UUID userId = family.getUserId();

		if (family.isRevoked()) {
			throw fail(userId, "SESSION_REVOKED", ipPrefix);
		}
		if (family.isAbsoluteExpired()) {
			family.revoke(RevokeReason.ABSOLUTE_EXPIRED);
			throw fail(userId, "SESSION_ABSOLUTE_EXPIRED", ipPrefix);
		}
		if (session.getRevokedAt() != null || session.getRotatedAt() != null) {
			// 이미 소모된(rotate/revoke된) 토큰이 다시 제출됨 — 탈취 의심. family 전체를 폐기한다.
			family.revoke(RevokeReason.REUSE_DETECTED);
			throw fail(userId, "TOKEN_REUSED", ipPrefix);
		}
		if (Instant.now().isAfter(session.getExpiresAt())) {
			// 단순 시간 만료 — 탈취 신호가 아니다. family를 REUSE_DETECTED로 몰지 않는다.
			throw fail(userId, "TOKEN_EXPIRED", ipPrefix);
		}

		session.markRotated();
		family.touchRotation();
		IssuedTokens tokens = sessionIssuanceService.issueRotation(family, ipPrefix);
		securityAuditService.record(userId, "AUTH_REFRESH", "SUCCESS", ipPrefix);
		return tokens;
	}

	private ApiException fail(UUID userId, String code, String ipPrefix) {
		String eventType = "TOKEN_REUSED".equals(code) ? "AUTH_REFRESH_REUSE_DETECTED" : "AUTH_REFRESH";
		securityAuditService.record(userId, eventType, "FAILURE", ipPrefix);
		return new ApiException(HttpStatus.UNAUTHORIZED, code, "세션이 만료되었습니다. 다시 로그인해 주세요.");
	}
}
