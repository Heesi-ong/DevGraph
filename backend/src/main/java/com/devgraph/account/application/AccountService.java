package com.devgraph.account.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.application.ReauthPurpose;
import com.devgraph.auth.application.ReauthService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.auth.infrastructure.UserStatus;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.web.FieldRules;

/**
 * 설계서 §9.1 AUTH-06 계정 탈퇴. 재인증 후 `ACTIVE → DELETION_PENDING`으로 바꾸고 모든 세션을 폐기한다.
 * 유예(7일, §21.5) 안에는 다시 로그인해 제한 세션으로 탈퇴를 취소할 수 있고, 지나면 {@link AccountPurgeService}가
 * 개인 데이터를 hard delete한다.
 */
@Service
public class AccountService {

	/** §21.5 정책표: 계정 탈퇴 유예 7일. */
	public static final long GRACE_DAYS = 7;

	private final UserRepository userRepository;
	private final AuthSessionFamilyRepository familyRepository;
	private final ReauthService reauthService;
	private final SecurityAuditService audit;

	public AccountService(UserRepository userRepository, AuthSessionFamilyRepository familyRepository,
			ReauthService reauthService, SecurityAuditService audit) {
		this.userRepository = userRepository;
		this.familyRepository = familyRepository;
		this.reauthService = reauthService;
		this.audit = audit;
	}

	/**
	 * 재인증 토큰 소비는 별도 트랜잭션으로 먼저 커밋되므로(§17.2.2) 이 메서드의 나머지가 실패해도 토큰은 다시 쓸 수 없다.
	 * 확인 문구는 본인 이메일이다(실수로 누르는 것을 막는 장치이며 비밀 값이 아니다).
	 */
	@Transactional
	public Instant requestDeletion(AuthenticatedUser actor, String confirmation, String reauthToken, String ipPrefix) {
		UserJpaEntity user = userRepository.findById(actor.userId()).orElseThrow();
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_ACCOUNT_STATE", "이미 탈퇴가 예약되어 있습니다.");
		}
		if (confirmation == null || !confirmation.trim().toLowerCase(Locale.ROOT).equals(user.getEmailNormalized())) {
			throw FieldRules.validation("confirmation", "CONFIRMATION_MISMATCH");
		}
		reauthService.consume(actor, reauthToken, ReauthPurpose.ACCOUNT_DELETE_REQUEST, actor.userId(), ipPrefix);

		Instant now = Instant.now();
		user.setStatus(UserStatus.DELETION_PENDING);
		user.setDeletionRequestedAt(now);
		int revoked = familyRepository.revokeAll(actor.userId(), RevokeReason.ACCOUNT_DELETION_REQUESTED, null);
		audit.record(actor.userId(), null, "ACCOUNT_DELETION_REQUEST", "SUCCESS", ipPrefix,
				Map.of("revokedSessions", Integer.toString(revoked)));
		return now.plus(GRACE_DAYS, ChronoUnit.DAYS);
	}

	/** 제한 세션(`DELETION_PENDING`)에서도 호출할 수 있다(§17.2.3). 프론트는 성공 직후 `/auth/refresh`로 제한 해제된 토큰을 받는다. */
	@Transactional
	public void cancelDeletion(UUID userId, String ipPrefix) {
		UserJpaEntity user = userRepository.findById(userId).orElseThrow();
		if (user.getStatus() != UserStatus.DELETION_PENDING) {
			throw new ApiException(HttpStatus.CONFLICT, "INVALID_ACCOUNT_STATE", "탈퇴가 예약된 계정이 아닙니다.");
		}
		user.setStatus(UserStatus.ACTIVE);
		user.setDeletionRequestedAt(null);
		audit.record(userId, null, "ACCOUNT_DELETION_CANCEL", "SUCCESS", ipPrefix, Map.of());
	}
}
