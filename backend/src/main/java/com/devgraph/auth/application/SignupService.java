package com.devgraph.auth.application;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiException;
import com.devgraph.workspace.application.WorkspaceProvisioningService;

/** 설계서 §9.1 AUTH-01, §14.2 POST /auth/signup. 가입 + 개인 Workspace 생성을 한 트랜잭션으로 묶는다. */
@Service
public class SignupService {

	private final UserRepository userRepository;
	private final WorkspaceProvisioningService workspaceProvisioningService;
	private final SessionIssuanceService sessionIssuanceService;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditService securityAuditService;

	public SignupService(UserRepository userRepository, WorkspaceProvisioningService workspaceProvisioningService,
			SessionIssuanceService sessionIssuanceService, PasswordEncoder passwordEncoder,
			SecurityAuditService securityAuditService) {
		this.userRepository = userRepository;
		this.workspaceProvisioningService = workspaceProvisioningService;
		this.sessionIssuanceService = sessionIssuanceService;
		this.passwordEncoder = passwordEncoder;
		this.securityAuditService = securityAuditService;
	}

	@Transactional
	public AuthResult signup(String email, String displayName, String rawPassword, String ipPrefix) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		if (userRepository.existsByEmailNormalized(normalized)) {
			// §14.1: 중복 이메일은 일반화된 409. 계정 존재 여부를 세부적으로 노출하지 않는다.
			throw new ApiException(HttpStatus.CONFLICT, "EMAIL_UNAVAILABLE", "가입할 수 없는 이메일입니다.");
		}
		UserJpaEntity user = new UserJpaEntity(UUID.randomUUID(), email, normalized, displayName,
				passwordEncoder.encode(rawPassword));
		userRepository.save(user);
		workspaceProvisioningService.createPersonalWorkspace(user.getId(), displayName);
		IssuedTokens tokens = sessionIssuanceService.issueNewFamily(user.getId(), null, ipPrefix);
		securityAuditService.record(user.getId(), "AUTH_SIGNUP", "SUCCESS", ipPrefix);
		return new AuthResult(user.getId(), user.getEmail(), user.getDisplayName(), tokens);
	}
}
