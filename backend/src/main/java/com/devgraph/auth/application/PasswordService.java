package com.devgraph.auth.application;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.ratelimit.RateLimitProperties;
import com.devgraph.common.ratelimit.RateLimiter;

/** 설계서 §9.1 AUTH-05 비밀번호 변경. 현재 비밀번호를 다시 확인하고, 원하면 다른 세션을 모두 폐기한다. */
@Service
public class PasswordService {

	private final UserRepository userRepository;
	private final AuthSessionFamilyRepository familyRepository;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditService audit;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties limits;

	public PasswordService(UserRepository userRepository, AuthSessionFamilyRepository familyRepository,
			PasswordEncoder passwordEncoder, SecurityAuditService audit, RateLimiter rateLimiter,
			RateLimitProperties limits) {
		this.userRepository = userRepository;
		this.familyRepository = familyRepository;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
	}

	/**
	 * 변경 성공은 `must_change_password`도 해제한다. 이미 발급된 Access JWT의 `restriction`은 바뀌지 않으므로
	 * 프론트는 성공 직후 `/auth/refresh`로 새 토큰을 받아야 한다(§17.2.3).
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void change(UUID userId, UUID currentFamilyId, String currentPassword, String newPassword,
			boolean revokeOtherSessions, String ipPrefix) {
		String limitKey = "password:" + userId;
		rateLimiter.check(limitKey, limits.getPasswordAttemptFailuresPerMinute(), limits.getWindow());
		UserJpaEntity user = userRepository.findById(userId).orElseThrow();
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			rateLimiter.hit(limitKey, limits.getWindow());
			audit.record(userId, null, "AUTH_PASSWORD_CHANGE", "FAILURE", ipPrefix, Map.of("reason", "INVALID_CREDENTIALS"));
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "현재 비밀번호가 올바르지 않습니다.");
		}
		rateLimiter.reset(limitKey);
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					java.util.List.of(new ApiError.FieldError("newPassword", "SAME_AS_CURRENT")));
		}
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		user.setMustChangePassword(false);
		int revoked = revokeOtherSessions ? familyRepository.revokeAll(userId, RevokeReason.PASSWORD_CHANGED, currentFamilyId) : 0;
		audit.record(userId, null, "AUTH_PASSWORD_CHANGE", "SUCCESS", ipPrefix,
				Map.of("revokedOtherSessions", Integer.toString(revoked)));
	}
}
