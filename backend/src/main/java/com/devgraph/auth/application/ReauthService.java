package com.devgraph.auth.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.infrastructure.AuthSessionFamilyRepository;
import com.devgraph.auth.infrastructure.UserJpaEntity;
import com.devgraph.auth.infrastructure.UserRepository;
import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.ratelimit.RateLimitProperties;
import com.devgraph.common.ratelimit.RateLimiter;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.security.TokenHasher;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §17.2.2 Recent reauthentication. 민감 작업(Export·영구 삭제·계정 탈퇴)은 비밀번호를 다시 확인해 받은
 * **1회용 토큰**(`X-Reauth-Token`)이 있어야 한다. 토큰은 기기 세션(family)당 1개이고, purpose와 대상이 묶여 있다.
 */
@Service
public class ReauthService {

	static final long TTL_MINUTES = 5;

	public record Issued(String token, ReauthPurpose purpose, Instant expiresAt) {
	}

	private final NamedParameterJdbcTemplate jdbc;
	private final UserRepository userRepository;
	private final AuthSessionFamilyRepository familyRepository;
	private final WorkspaceQueryService workspaceQueryService;
	private final PasswordEncoder passwordEncoder;
	private final SecurityAuditService audit;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties limits;
	private final TransactionTemplate consumeTransaction;

	public ReauthService(NamedParameterJdbcTemplate jdbc, UserRepository userRepository,
			AuthSessionFamilyRepository familyRepository, WorkspaceQueryService workspaceQueryService,
			PasswordEncoder passwordEncoder, SecurityAuditService audit, RateLimiter rateLimiter,
			RateLimitProperties limits, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.userRepository = userRepository;
		this.familyRepository = familyRepository;
		this.workspaceQueryService = workspaceQueryService;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		// 소비는 뒤따르는 민감 작업과 별개의 짧은 트랜잭션으로 먼저 커밋한다(§17.2.2).
		this.consumeTransaction = new TransactionTemplate(transactionManager);
		this.consumeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** `POST /auth/reauth`. 검증 순서와 오류 코드는 §17.2.2의 표를 따른다. */
	public Issued issue(AuthenticatedUser actor, String password, String rawPurpose, UUID targetId, String ipPrefix) {
		ReauthPurpose purpose = parsePurpose(rawPurpose);
		boolean needsTarget = purpose == ReauthPurpose.NODE_PERMANENT_DELETE;
		if (needsTarget && targetId == null) {
			throw bad("REAUTH_TARGET_REQUIRED", "targetId");
		}
		if (!needsTarget && targetId != null) {
			throw bad("REAUTH_TARGET_NOT_ALLOWED", "targetId");
		}
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(actor.userId());
		UUID serverTarget = switch (purpose) {
			case EXPORT_CREATE, IMPORT_CREATE -> workspaceId;
			case ACCOUNT_DELETE_REQUEST -> actor.userId();
			case NODE_PERMANENT_DELETE -> {
				// 다른 Workspace의 Node는 없는 것과 같다(§17.1).
				Integer found = jdbc.queryForObject("SELECT count(*) FROM knowledge_nodes WHERE id = :id AND workspace_id = :ws",
						Map.of("id", targetId, "ws", workspaceId), Integer.class);
				if (found == null || found == 0) {
					throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
				}
				yield targetId;
			}
		};

		String limitKey = "reauth:" + actor.userId();
		rateLimiter.check(limitKey, limits.getPasswordAttemptFailuresPerMinute(), limits.getWindow());
		UserJpaEntity user = userRepository.findById(actor.userId()).orElseThrow();
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			rateLimiter.hit(limitKey, limits.getWindow());
			audit.record(actor.userId(), workspaceId, "AUTH_REAUTH_ISSUE", "FAILURE", ipPrefix,
					Map.of("purpose", purpose.name(), "reason", "INVALID_CREDENTIALS", "familyId", actor.familyId().toString()));
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "비밀번호가 올바르지 않습니다.");
		}
		rateLimiter.reset(limitKey);

		String rawToken = TokenHasher.newOpaqueToken();
		Instant expiresAt = Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES);
		// family당 1행. 재발급은 같은 행을 갈아 끼우고 used_at을 되돌린다.
		jdbc.update("""
				INSERT INTO reauth_tokens (token_family_id, purpose, target_id, token_hash, expires_at)
				VALUES (:family, :purpose, :target, :hash, :expires)
				ON CONFLICT (token_family_id) DO UPDATE SET purpose = EXCLUDED.purpose, target_id = EXCLUDED.target_id,
				  token_hash = EXCLUDED.token_hash, expires_at = EXCLUDED.expires_at, used_at = NULL, updated_at = now()""",
				new MapSqlParameterSource().addValue("family", actor.familyId()).addValue("purpose", purpose.name())
						.addValue("target", serverTarget).addValue("hash", TokenHasher.sha256(rawToken))
						.addValue("expires", Timestamp.from(expiresAt)));
		audit.record(actor.userId(), workspaceId, "AUTH_REAUTH_ISSUE", "SUCCESS", ipPrefix,
				Map.of("purpose", purpose.name(), "targetId", serverTarget.toString(), "familyId", actor.familyId().toString()));
		return new Issued(rawToken, purpose, expiresAt);
	}

	/**
	 * 민감 API가 `X-Reauth-Token`을 소비한다(§17.2.2 표). 누락·없음·만료·사용됨·다른 세션·폐기된 세션은 **모두 같은**
	 * `401 REAUTH_REQUIRED`로 응답해 정보를 흘리지 않고, purpose/대상이 다르면 `403`이다. 소비는 조건부 UPDATE 하나로
	 * 원자적이며 별도 트랜잭션으로 먼저 커밋되므로 뒤따르는 작업이 실패해도 토큰은 다시 쓸 수 없다.
	 */
	public void consume(AuthenticatedUser actor, String rawToken, ReauthPurpose expectedPurpose, UUID expectedTarget,
			String ipPrefix) {
		consume(verify(actor, rawToken, expectedPurpose, expectedTarget, ipPrefix), actor, expectedPurpose, ipPrefix);
	}

	/** 검증을 통과한 토큰(아직 소비하지 않음). {@link #consume(Verified, AuthenticatedUser, ReauthPurpose, String)}로 소비한다. */
	public record Verified(UUID tokenId) {
	}

	/**
	 * 토큰이 이 요청에 쓸 수 있는지 **소비하지 않고** 확인한다. 민감 작업이 대상 상태 같은 다른 전제를 확인하기 전에
	 * 호출해, 잘못된 토큰이 대상의 상태를 드러내지 않게 하고(항상 401/403) 전제 실패가 토큰을 소진하지 않게 한다.
	 */
	public Verified verify(AuthenticatedUser actor, String rawToken, ReauthPurpose expectedPurpose, UUID expectedTarget,
			String ipPrefix) {
		Map<String, String> context = Map.of("purpose", expectedPurpose.name(), "familyId", actor.familyId().toString());
		if (rawToken == null || rawToken.isBlank()) {
			throw denied(actor, ipPrefix, "MISSING", context);
		}
		Optional<Row> found = jdbc.query("""
				SELECT id, token_family_id, purpose, target_id, expires_at, used_at FROM reauth_tokens WHERE token_hash = :hash""",
				new MapSqlParameterSource("hash", TokenHasher.sha256(rawToken)), rs -> {
					if (!rs.next()) {
						return Optional.<Row>empty();
					}
					Timestamp used = rs.getTimestamp("used_at");
					return Optional.of(new Row(rs.getObject("id", UUID.class), rs.getObject("token_family_id", UUID.class),
							rs.getString("purpose"), rs.getObject("target_id", UUID.class),
							rs.getTimestamp("expires_at").toInstant(), used == null ? null : used.toInstant()));
				});
		if (found.isEmpty() || found.get().usedAt() != null || !found.get().expiresAt().isAfter(Instant.now())) {
			throw denied(actor, ipPrefix, "INVALID_OR_EXPIRED", context);
		}
		Row row = found.get();
		if (!row.familyId().equals(actor.familyId())) {
			throw denied(actor, ipPrefix, "FAMILY_MISMATCH", context);
		}
		boolean familyAlive = familyRepository.findById(row.familyId()).map(f -> !f.isRevoked()).orElse(false);
		if (!familyAlive) {
			throw denied(actor, ipPrefix, "FAMILY_REVOKED", context);
		}
		if (!row.purpose().equals(expectedPurpose.name())) {
			audit.record(actor.userId(), null, "AUTH_REAUTH_CONSUME", "FAILURE", ipPrefix,
					Map.of("purpose", expectedPurpose.name(), "reason", "PURPOSE_MISMATCH", "familyId", actor.familyId().toString()));
			throw new ApiException(HttpStatus.FORBIDDEN, "REAUTH_PURPOSE_MISMATCH", "이 작업에 사용할 수 없는 재인증입니다.");
		}
		if (!Objects.equals(row.targetId(), expectedTarget)) {
			audit.record(actor.userId(), null, "AUTH_REAUTH_CONSUME", "FAILURE", ipPrefix,
					Map.of("purpose", expectedPurpose.name(), "reason", "TARGET_MISMATCH", "familyId", actor.familyId().toString()));
			throw new ApiException(HttpStatus.FORBIDDEN, "REAUTH_TARGET_MISMATCH", "다른 대상에 대한 재인증입니다.");
		}
		return new Verified(row.id());
	}

	/** 검증된 토큰을 조건부 UPDATE 하나로 원자적으로 소비한다. 동시에 먼저 소비한 요청이 있으면 `401`. */
	public void consume(Verified verified, AuthenticatedUser actor, ReauthPurpose purpose, String ipPrefix) {
		Boolean consumed = consumeTransaction.execute(status -> jdbc.query("""
				UPDATE reauth_tokens SET used_at = now(), updated_at = now()
				WHERE id = :id AND used_at IS NULL AND expires_at > now() RETURNING id""",
				new MapSqlParameterSource("id", verified.tokenId()), ResultSetRowCountExtractor.INSTANCE));
		if (!Boolean.TRUE.equals(consumed)) {
			throw denied(actor, ipPrefix, "ALREADY_CONSUMED",
					Map.of("purpose", purpose.name(), "familyId", actor.familyId().toString()));
		}
		audit.record(actor.userId(), null, "AUTH_REAUTH_CONSUME", "SUCCESS", ipPrefix,
				Map.of("purpose", purpose.name(), "familyId", actor.familyId().toString()));
	}

	private ApiException denied(AuthenticatedUser actor, String ipPrefix, String reason, Map<String, String> context) {
		java.util.HashMap<String, String> metadata = new java.util.HashMap<>(context);
		metadata.put("reason", reason);
		audit.record(actor.userId(), null, "AUTH_REAUTH_CONSUME", "FAILURE", ipPrefix, metadata);
		return new ApiException(HttpStatus.UNAUTHORIZED, "REAUTH_REQUIRED", "다시 비밀번호를 확인해 주세요.");
	}

	private static ReauthPurpose parsePurpose(String raw) {
		try {
			return ReauthPurpose.valueOf(raw == null ? "" : raw);
		} catch (IllegalArgumentException e) {
			throw bad("INVALID_REAUTH_PURPOSE", "purpose");
		}
	}

	private static ApiException bad(String code, String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, "입력값을 확인해 주세요.",
				java.util.List.of(new ApiError.FieldError(field, code)));
	}

	private record Row(UUID id, UUID familyId, String purpose, UUID targetId, Instant expiresAt, Instant usedAt) {
	}

	/** `UPDATE ... RETURNING`이 한 행이라도 돌려줬는지만 본다. */
	private static final class ResultSetRowCountExtractor implements org.springframework.jdbc.core.ResultSetExtractor<Boolean> {
		static final ResultSetRowCountExtractor INSTANCE = new ResultSetRowCountExtractor();

		@Override
		public Boolean extractData(java.sql.ResultSet rs) throws java.sql.SQLException {
			return rs.next();
		}
	}
}
