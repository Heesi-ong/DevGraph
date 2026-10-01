package com.devgraph.export.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.SecurityAuditService;
import com.devgraph.auth.application.ReauthPurpose;
import com.devgraph.auth.application.ReauthService;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.ratelimit.RateLimitProperties;
import com.devgraph.common.ratelimit.RateLimiter;
import com.devgraph.common.security.AuthenticatedUser;
import com.devgraph.common.security.TokenHasher;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.8 EXPT-01~04. 인증된 **비동기 Job 단일 방식**: 생성(`PENDING`) → 폴러가 처리 → `COMPLETED`+일회성 token 또는 `FAILED`.
 * 생성은 재인증(`EXPORT_CREATE`), 사용자당 동시 1개, 10분에 1회로 제한한다.
 */
@Service
public class ExportService {

	public record JobView(UUID jobId, String status, String downloadUrl, Instant expiresAt, String failureReason) {
	}

	private final NamedParameterJdbcTemplate jdbc;
	private final ReauthService reauthService;
	private final WorkspaceQueryService workspaceQueryService;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties limits;
	private final ExportProperties properties;
	private final SecurityAuditService audit;
	private final String publicBaseUrl;

	public ExportService(NamedParameterJdbcTemplate jdbc, ReauthService reauthService,
			WorkspaceQueryService workspaceQueryService, RateLimiter rateLimiter, RateLimitProperties limits,
			ExportProperties properties, SecurityAuditService audit,
			@org.springframework.beans.factory.annotation.Value("${devgraph.public-base-url:http://localhost:8080}") String publicBaseUrl) {
		this.jdbc = jdbc;
		this.reauthService = reauthService;
		this.workspaceQueryService = workspaceQueryService;
		this.rateLimiter = rateLimiter;
		this.limits = limits;
		this.properties = properties;
		this.audit = audit;
		this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
	}

	/**
	 * `POST /exports`. 순서: 재인증 검증(소비 안 함, 401/403) → 이미 진행 중인지(409) → 사용량 제한(429) → 재인증 소비 →
	 * job 생성. 409/429로 거절돼도 1회용 토큰은 소진되지 않는다. 동시 요청 경합은 DB의 부분 UNIQUE 인덱스가 막는다.
	 */
	@Transactional
	public UUID create(AuthenticatedUser actor, String reauthToken, boolean includeArchived, String ipPrefix) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(actor.userId());
		ReauthService.Verified verified = reauthService.verify(actor, reauthToken, ReauthPurpose.EXPORT_CREATE, workspaceId, ipPrefix);
		if (hasActiveJob(actor.userId())) {
			throw inProgress();
		}
		if (limits.getExportIntervalMinutes() > 0) {
			rateLimiter.acquire("export:" + actor.userId(), 1, java.time.Duration.ofMinutes(limits.getExportIntervalMinutes()));
		}
		reauthService.consume(verified, actor, ReauthPurpose.EXPORT_CREATE, ipPrefix);
		try {
			UUID id = jdbc.queryForObject("""
					INSERT INTO export_jobs (workspace_id, requested_by, include_archived) VALUES (:ws, :user, :archived) RETURNING id""",
					new MapSqlParameterSource().addValue("ws", workspaceId).addValue("user", actor.userId())
							.addValue("archived", includeArchived), UUID.class);
			audit.record(actor.userId(), workspaceId, "EXPORT_CREATE", "SUCCESS", ipPrefix, Map.of("jobId", id.toString()));
			return id;
		} catch (DuplicateKeyException e) {
			throw inProgress(); // 동시에 다른 요청이 먼저 만들었다(uq_export_jobs__active_per_user).
		}
	}

	/**
	 * `GET /exports/{jobId}`. 완료된 job을 조회할 때마다 **새 일회성 download token**을 발급한다(이전 token은 무효).
	 * 파일 보관 기한이 지났으면 `410 EXPIRED`다. 다른 Workspace의 job은 `404`.
	 */
	@Transactional
	public JobView get(AuthenticatedUser actor, UUID jobId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(actor.userId());
		Map<String, Object> job = find(jobId, workspaceId).orElseThrow(ExportService::notFound);
		String status = (String) job.get("status");
		Timestamp fileDeadline = (Timestamp) job.get("expires_at");
		// 정리 배치가 아직 안 돌았어도 보관 기한이 지난 파일은 이미 만료로 본다.
		boolean pastDeadline = "COMPLETED".equals(status) && fileDeadline != null && fileDeadline.toInstant().isBefore(Instant.now());
		if ("EXPIRED".equals(status) || pastDeadline) {
			throw new ApiException(HttpStatus.GONE, "EXPIRED", "내보내기 파일의 보관 기한이 지났습니다. 다시 내보내 주세요.");
		}
		if (!"COMPLETED".equals(status)) {
			return new JobView(jobId, status, null, null, (String) job.get("failure_reason"));
		}
		String rawToken = TokenHasher.newOpaqueToken();
		Instant tokenExpiry = Instant.now().plus(properties.getDownloadTokenTtl());
		jdbc.update("UPDATE export_jobs SET download_token_hash = :hash, download_token_expires_at = :exp WHERE id = :id",
				new MapSqlParameterSource().addValue("hash", TokenHasher.sha256(rawToken))
						.addValue("exp", Timestamp.from(tokenExpiry)).addValue("id", jobId));
		return new JobView(jobId, status, publicBaseUrl + "/api/v1/exports/" + jobId + "/download?token=" + rawToken, tokenExpiry, null);
	}

	/** 최근 job 목록(화면이 새로고침 뒤에도 진행 상황을 보여 주기 위한 보조 조회). token은 만들지 않는다. */
	@Transactional(readOnly = true)
	public List<JobView> recent(AuthenticatedUser actor) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(actor.userId());
		return jdbc.query("""
				SELECT id, status, failure_reason FROM export_jobs WHERE workspace_id = :ws AND requested_by = :user
				ORDER BY created_at DESC LIMIT 10""", new MapSqlParameterSource().addValue("ws", workspaceId)
				.addValue("user", actor.userId()),
				(rs, i) -> new JobView(rs.getObject("id", UUID.class), rs.getString("status"), null, null, rs.getString("failure_reason")));
	}

	/** 다운로드 대상. token은 한 번만 쓸 수 있고(찾는 즉시 무효화) 만료·불일치는 모두 같은 404다. */
	public record Download(Path file, UUID jobId) {
	}

	@Transactional
	public Download openDownload(UUID jobId, String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw notFound();
		}
		Optional<Map<String, Object>> found = jdbc.queryForList("""
				UPDATE export_jobs SET download_token_hash = NULL, download_token_expires_at = NULL
				WHERE id = :id AND status = 'COMPLETED' AND download_token_hash = :hash AND download_token_expires_at > now() AND expires_at > now()
				RETURNING workspace_id, requested_by, file_storage_key""",
				new MapSqlParameterSource().addValue("id", jobId).addValue("hash", TokenHasher.sha256(rawToken))).stream().findFirst();
		Map<String, Object> job = found.orElseThrow(ExportService::notFound);
		Path file = resolve((String) job.get("file_storage_key"));
		if (!Files.isRegularFile(file)) {
			throw new ApiException(HttpStatus.GONE, "EXPIRED", "내보내기 파일의 보관 기한이 지났습니다. 다시 내보내 주세요.");
		}
		audit.record((UUID) job.get("requested_by"), (UUID) job.get("workspace_id"), "EXPORT_DOWNLOAD", "SUCCESS", null,
				Map.of("jobId", jobId.toString()));
		return new Download(file, jobId);
	}

	/** 다운로드가 끝나면(성공이든 중단이든) 파일을 지우고 EXPIRED로 바꾼다(§9.8: 다운로드 완료 후 1시간 이내 삭제 → 즉시). */
	public void finishDownload(Download download) {
		try {
			Files.deleteIfExists(download.file());
		} catch (IOException e) {
			// 지우지 못해도 만료 배치가 다시 정리한다.
		}
		jdbc.update("UPDATE export_jobs SET status = 'EXPIRED', file_storage_key = NULL WHERE id = :id",
				new MapSqlParameterSource("id", download.jobId()));
	}

	Path resolve(String storageKey) {
		// 저장 key는 서버가 만든 uuid.zip이다. 그래도 경로 요소가 섞이면 거부한다(방어).
		if (storageKey == null || !storageKey.matches("[0-9a-f-]{36}\\.zip")) {
			throw notFound();
		}
		return Path.of(properties.getTempDir()).resolve(storageKey);
	}

	private boolean hasActiveJob(UUID userId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM export_jobs WHERE requested_by = :user AND status IN ('PENDING','PROCESSING')",
				new MapSqlParameterSource("user", userId), Integer.class);
		return count != null && count > 0;
	}

	private Optional<Map<String, Object>> find(UUID jobId, UUID workspaceId) {
		return jdbc.queryForList("SELECT id, status, failure_reason, expires_at FROM export_jobs WHERE id = :id AND workspace_id = :ws",
				new MapSqlParameterSource().addValue("id", jobId).addValue("ws", workspaceId)).stream().findFirst();
	}

	private static ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "EXPORT_IN_PROGRESS", "이미 진행 중인 내보내기가 있습니다.");
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
	}
}
