package com.devgraph.auth.application;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiException;

/** 설계서 §9.1 AUTH-02, §14.2 POST /auth/login. */
@Service
public class LoginService {

	// 존재하지 않는 이메일에 대해서도 동일한 지연으로 응답해 계정 존재 여부를 타이밍으로 노출하지 않는다.
	private static final String DUMMY_HASH =
			"$argon2id$v=19$m=16384,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

	private final UserRepository userRepository;
	private final SessionIssuanceService sessionIssuanceService;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditService securityAuditService;

	public LoginService(UserRepository userRepository, SessionIssuanceService sessionIssuanceService,
			PasswordEncoder passwordEncoder, SecurityAuditService securityAuditService) {
		this.userRepository = userRepository;
		this.sessionIssuanceService = sessionIssuanceService;
		this.passwordEncoder = passwordEncoder;
		this.securityAuditService = securityAuditService;
	}

	@Transactional
	public AuthResult login(String email, String rawPassword, String deviceLabel, String ipPrefix) {
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		Optional<UserJpaEntity> found = userRepository.findByEmailNormalized(normalized);
		boolean passwordMatches = passwordEncoder.matches(rawPassword,
				found.map(UserJpaEntity::getPasswordHash).orElse(DUMMY_HASH));

		if (found.isEmpty() || !passwordMatches) {
			securityAuditService.record(found.map(UserJpaEntity::getId).orElse(null), "AUTH_LOGIN", "FAILURE", ipPrefix);
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
		}

		UserJpaEntity user = found.get();
		IssuedTokens tokens = sessionIssuanceService.issueNewFamily(user.getId(), deviceLabel, ipPrefix);
		securityAuditService.record(user.getId(), "AUTH_LOGIN", "SUCCESS", ipPrefix);
		return new AuthResult(user.getId(), user.getEmail(), user.getDisplayName(), tokens);
	}
}
