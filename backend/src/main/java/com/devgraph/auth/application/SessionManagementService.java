package com.devgraph.auth.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.auth.infrastructure.SessionQueryRepository;
import com.devgraph.auth.infrastructure.SessionQueryRepository.SessionRow;
import com.devgraph.common.error.ApiException;

/** 설계서 §14.2 GET/DELETE /auth/sessions, POST /auth/logout — 전부 family 단위(§17.2.1). */
@Service
public class SessionManagementService {

	private final AuthSessionFamilyRepository familyRepository;
	private final SecurityAuditService securityAuditService;
	private final SessionQueryRepository sessionQueryRepository;
	private final TransactionTemplate revokeTransaction;

	public SessionManagementService(AuthSessionFamilyRepository familyRepository,
			SecurityAuditService securityAuditService, SessionQueryRepository sessionQueryRepository,
			PlatformTransactionManager transactionManager) {
		this.familyRepository = familyRepository;
		this.securityAuditService = securityAuditService;
		this.sessionQueryRepository = sessionQueryRepository;
		this.revokeTransaction = new TransactionTemplate(transactionManager);
	}

	@Transactional(readOnly = true)
	public List<SessionRow> listActiveSessions(UUID userId) {
		return sessionQueryRepository.active(userId);
	}

	public void revoke(UUID userId, UUID familyId, RevokeReason reason, String ipPrefix) {
		Boolean changed = revokeTransaction.execute(status -> {
			AuthSessionFamilyJpaEntity family = familyRepository.findLockedByIdAndUserId(familyId, userId)
					.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "세션을 찾을 수 없습니다."));
			if (family.isRevoked()) return false;
			family.revoke(reason);
			return true;
		});
		if (Boolean.TRUE.equals(changed)) {
			securityAuditService.record(userId, "AUTH_SESSION_REVOKED", "SUCCESS", ipPrefix);
		}
	}
}
