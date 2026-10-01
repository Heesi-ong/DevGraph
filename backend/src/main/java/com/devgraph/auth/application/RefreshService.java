package com.devgraph.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
	private final TransactionTemplate rotationTransaction;

	public RefreshService(AuthSessionRepository sessionRepository, AuthSessionFamilyRepository familyRepository,
			SessionIssuanceService sessionIssuanceService, SecurityAuditService securityAuditService,
			PlatformTransactionManager transactionManager) {
		this.sessionRepository = sessionRepository;
		this.familyRepository = familyRepository;
		this.sessionIssuanceService = sessionIssuanceService;
		this.securityAuditService = securityAuditService;
		this.rotationTransaction = new TransactionTemplate(transactionManager);
	}

	public IssuedTokens refresh(String rawRefreshToken, String ipPrefix) {
		RotationResult result = Objects.requireNonNull(rotationTransaction.execute(
				status -> rotate(TokenHasher.sha256(rawRefreshToken), ipPrefix)));
		// 잠금/연결을 반환한 뒤 REQUIRES_NEW 감사 기록: 대량 동시 요청이 연결 풀을 고갈시키지 않는다.
		String event = "TOKEN_REUSED".equals(result.code()) ? "AUTH_REFRESH_REUSE_DETECTED" : "AUTH_REFRESH";
		securityAuditService.record(result.userId(), event, result.code() == null ? "SUCCESS" : "FAILURE", ipPrefix);
		if (result.code() != null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, result.code(), "세션이 만료되었습니다. 다시 로그인해 주세요.");
		}
		return result.tokens();
	}

	private RotationResult rotate(byte[] hash, String ipPrefix) {
		UUID familyId = sessionRepository.findFamilyIdByTokenHash(hash).orElse(null);
		if (familyId == null) {
			return rejected(null, "TOKEN_EXPIRED");
		}

		// family 잠금을 먼저 잡아 refresh/reuse/logout이 동일한 순서로 직렬화되도록 한다.
		AuthSessionFamilyJpaEntity family = familyRepository.findLockedById(familyId).orElse(null);
		if (family == null) {
			return rejected(null, "TOKEN_EXPIRED");
		}
		UUID userId = family.getUserId();
		AuthSessionJpaEntity session = sessionRepository.findByRefreshTokenHash(hash).orElse(null);
		if (session == null) {
			return rejected(userId, "TOKEN_EXPIRED");
		}

		if (family.isRevoked()) {
			return rejected(userId, "SESSION_REVOKED");
		}
		if (!Instant.now().isBefore(family.getAbsoluteExpiresAt())) {
			family.revoke(RevokeReason.ABSOLUTE_EXPIRED);
			return rejected(userId, "SESSION_ABSOLUTE_EXPIRED");
		}
		if (session.getRevokedAt() != null || session.getRotatedAt() != null) {
			// 이미 소모된(rotate/revoke된) 토큰이 다시 제출됨 — 탈취 의심. family 전체를 폐기한다.
			family.revoke(RevokeReason.REUSE_DETECTED);
			return rejected(userId, "TOKEN_REUSED");
		}
		if (!Instant.now().isBefore(session.getExpiresAt())) {
			// 단순 시간 만료 — 탈취 신호가 아니다. family를 REUSE_DETECTED로 몰지 않는다.
			return rejected(userId, "TOKEN_EXPIRED");
		}

		if (sessionRepository.consume(session.getId()) != 1) {
			return rejected(userId, "TOKEN_EXPIRED");
		}
		family.touchRotation();
		IssuedTokens tokens = sessionIssuanceService.issueRotation(family, ipPrefix);
		return new RotationResult(userId, tokens, null);
	}

	private static RotationResult rejected(UUID userId, String code) {
		return new RotationResult(userId, null, code);
	}

	private record RotationResult(UUID userId, IssuedTokens tokens, String code) {
	}
}
