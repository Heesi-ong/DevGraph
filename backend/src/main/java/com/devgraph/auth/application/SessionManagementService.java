package com.devgraph.auth.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyJpaEntity;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.common.error.ApiException;

/** 설계서 §14.2 GET/DELETE /auth/sessions, POST /auth/logout — 전부 family 단위(§17.2.1). */
@Service
public class SessionManagementService {

	private final AuthSessionFamilyRepository familyRepository;
	private final SecurityAuditService securityAuditService;

	public SessionManagementService(AuthSessionFamilyRepository familyRepository,
			SecurityAuditService securityAuditService) {
		this.familyRepository = familyRepository;
		this.securityAuditService = securityAuditService;
	}

	public List<AuthSessionFamilyJpaEntity> listActiveSessions(UUID userId) {
		return familyRepository.findByUserIdAndRevokedAtIsNullOrderByLastRotatedAtDesc(userId);
	}

	@Transactional
	public void revoke(UUID userId, UUID familyId, RevokeReason reason, String ipPrefix) {
		AuthSessionFamilyJpaEntity family = familyRepository.findByIdAndUserId(familyId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "세션을 찾을 수 없습니다."));
		if (family.isRevoked()) {
			return; // 멱등 처리(설계서 §14.2 DELETE /auth/sessions/{id}).
		}
		family.revoke(reason);
		securityAuditService.record(userId, "AUTH_SESSION_REVOKED", "SUCCESS", ipPrefix);
	}
}
