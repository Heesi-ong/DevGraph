package com.devgraph.auth.application;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.RevokeReason;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiException;

/**
 * 설계서 §17.8 관리자 전용 계정 복구. 이메일 인프라 없이 출시하는 비공개 베타에서 비밀번호를 잊은 사용자를
 * 복구하는 유일한 경로이며, 운영자가 서버에서 CLI(`AdminCommandRunner`)로만 호출한다(HTTP로 노출하지 않는다).
 * 임시 비밀번호를 만들고, 다음 로그인에서 비밀번호 변경을 강제하며, 기존 세션을 모두 폐기한다.
 */
@Service
public class AdminAccountService {

	private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final UserRepository userRepository;
	private final AuthSessionFamilyRepository familyRepository;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditService audit;

	public AdminAccountService(UserRepository userRepository, AuthSessionFamilyRepository familyRepository,
			PasswordEncoder passwordEncoder, SecurityAuditService audit) {
		this.userRepository = userRepository;
		this.familyRepository = familyRepository;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
	}

	/** 임시 비밀번호(평문)를 돌려준다. 호출자(CLI)가 운영자 터미널에만 출력하고 로그에는 남기지 않는다. */
	@Transactional
	public String forcePasswordReset(String email) {
		UserJpaEntity user = userRepository.findByEmailNormalized(email.trim().toLowerCase(Locale.ROOT))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "계정을 찾을 수 없습니다."));
		StringBuilder temporary = new StringBuilder();
		for (int i = 0; i < 20; i++) {
			temporary.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		user.setPasswordHash(passwordEncoder.encode(temporary));
		user.setMustChangePassword(true);
		int revoked = familyRepository.revokeAll(user.getId(), RevokeReason.PASSWORD_CHANGED, null);
		audit.record(user.getId(), null, "ADMIN_FORCE_PASSWORD_RESET", "SUCCESS", null,
				Map.of("via", "cli", "revokedSessions", Integer.toString(revoked)));
		return temporary.toString();
	}
}
