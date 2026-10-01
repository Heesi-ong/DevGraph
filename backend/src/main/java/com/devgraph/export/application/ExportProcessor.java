package com.devgraph.export.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설계서 §9.8: 별도 worker 없이 백엔드 프로세스 안의 `@Scheduled` 폴러가 `PENDING` job을 하나씩 집어 처리한다.
 * 동시 인스턴스의 중복 처리는 `FOR UPDATE SKIP LOCKED`로 막는다. 실패는 최대 2회 자동 재시도 후 `FAILED`로 확정하고,
 * 크기 한도 초과는 재시도해도 같으므로 바로 확정한다. 실패 사유에는 내부 상세를 넣지 않는다.
 */
@Component
public class ExportProcessor {

	private static final Logger log = LoggerFactory.getLogger(ExportProcessor.class);

	private record Claimed(UUID id, UUID workspaceId, boolean includeArchived, int retryCount) {
	}

	private final NamedParameterJdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final ExportBuilder builder;
	private final ExportProperties properties;
	private final MeterRegistry metrics;

	public ExportProcessor(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			ExportBuilder builder, ExportProperties properties, MeterRegistry metrics) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(transactionManager);
		this.builder = builder;
		this.properties = properties;
		this.metrics = metrics;
	}

	/** job 하나를 처리한다. 처리할 것이 없으면 false. */
	public boolean processNext() {
		Claimed job = claim();
		if (job == null) {
			return false;
		}
		Path dir = Path.of(properties.getTempDir());
		String key = UUID.randomUUID() + ".zip";
		Path target = dir.resolve(key);
		long started = System.nanoTime();
		try {
			Files.createDirectories(dir);
			ExportBuilder.Result result = builder.build(job.workspaceId(), job.includeArchived(), target);
			Instant now = Instant.now();
			jdbc.update("""
					UPDATE export_jobs SET status = 'COMPLETED', file_storage_key = :key, file_size_bytes = :size,
					  manifest_checksum = :checksum, completed_at = :now, expires_at = :expires, failure_reason = NULL
					WHERE id = :id""", new MapSqlParameterSource().addValue("key", key).addValue("size", result.sizeBytes())
					.addValue("checksum", result.manifestChecksum()).addValue("now", Timestamp.from(now))
					.addValue("expires", Timestamp.from(now.plus(properties.getFileRetention()))).addValue("id", job.id()));
			Timer.builder("devgraph.export.duration").tag("outcome", "completed").register(metrics)
					.record(Duration.ofNanos(System.nanoTime() - started));
			metrics.summary("devgraph.export.size.bytes").record(result.sizeBytes());
		} catch (ExportBuilder.ExportTooLargeException e) {
			fail(job, "EXPORT_TOO_LARGE");
		} catch (RuntimeException | IOException e) {
			// 원인은 서버 로그에만 남기고 사용자에게는 일반화된 사유만 준다. job id는 로그와 대조하는 용도다.
			log.error("export failed jobId={} attempt={}", job.id(), job.retryCount() + 1, e);
			if (job.retryCount() < properties.getMaxRetries()) {
				jdbc.update("UPDATE export_jobs SET status = 'PENDING', retry_count = retry_count + 1, started_at = NULL WHERE id = :id",
						new MapSqlParameterSource("id", job.id()));
			} else {
				fail(job, "EXPORT_FAILED");
			}
			deleteQuietly(target);
		}
		return true;
	}

	/**
	 * 정리 배치: 보관 기한이 지난 완료 job의 파일을 지우고 EXPIRED로, 멈춘 PROCESSING job은 대기열로 되돌린다,
	 * 임시 디렉터리의 오래된 미완성 파일(`.zip`이 아닌 잔여물 포함)도 지운다. 돌려주는 값은 정리한 job 수.
	 */
	public int cleanup(Instant now) {
		int cleaned = 0;
		List<Map<String, Object>> expired = jdbc.queryForList("""
				SELECT id, file_storage_key FROM export_jobs WHERE status = 'COMPLETED' AND expires_at < :now""",
				new MapSqlParameterSource("now", Timestamp.from(now)));
		for (Map<String, Object> job : expired) {
			String key = (String) job.get("file_storage_key");
			if (key != null && key.matches("[0-9a-f-]{36}\\.zip")) {
				deleteQuietly(Path.of(properties.getTempDir()).resolve(key));
			}
			jdbc.update("UPDATE export_jobs SET status = 'EXPIRED', file_storage_key = NULL, download_token_hash = NULL WHERE id = :id",
					new MapSqlParameterSource("id", job.get("id")));
			cleaned++;
		}
		cleaned += jdbc.update("""
				UPDATE export_jobs SET status = 'PENDING', retry_count = retry_count + 1, started_at = NULL
				WHERE status = 'PROCESSING' AND started_at < :stuck AND retry_count < :max""",
				new MapSqlParameterSource().addValue("stuck", Timestamp.from(now.minus(properties.getStuckAfter())))
						.addValue("max", properties.getMaxRetries()));
		cleaned += jdbc.update("""
				UPDATE export_jobs SET status = 'FAILED', failure_reason = 'EXPORT_FAILED'
				WHERE status = 'PROCESSING' AND started_at < :stuck AND retry_count >= :max""",
				new MapSqlParameterSource().addValue("stuck", Timestamp.from(now.minus(properties.getStuckAfter())))
						.addValue("max", properties.getMaxRetries()));
		sweepOrphans(now);
		return cleaned;
	}

	private Claimed claim() {
		return tx.execute(status -> jdbc.query("""
				SELECT id, workspace_id, include_archived, retry_count FROM export_jobs WHERE status = 'PENDING'
				ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED""", rs -> {
			if (!rs.next()) {
				return null;
			}
			Claimed claimed = new Claimed(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
					rs.getBoolean("include_archived"), rs.getInt("retry_count"));
			jdbc.update("UPDATE export_jobs SET status = 'PROCESSING', started_at = now() WHERE id = :id",
					new MapSqlParameterSource("id", claimed.id()));
			return claimed;
		}));
	}

	private void fail(Claimed job, String reason) {
		jdbc.update("UPDATE export_jobs SET status = 'FAILED', failure_reason = :reason, completed_at = now() WHERE id = :id",
				new MapSqlParameterSource().addValue("reason", reason).addValue("id", job.id()));
		metrics.counter("devgraph.export.failed", "reason", reason).increment();
	}

	private void sweepOrphans(Instant now) {
		Path dir = Path.of(properties.getTempDir());
		if (!Files.isDirectory(dir)) {
			return;
		}
		Instant cutoff = now.minus(properties.getFileRetention()).minus(Duration.ofHours(1));
		try (var files = Files.list(dir)) {
			for (Path file : (Iterable<Path>) files::iterator) {
				String name = file.getFileName().toString();
				Integer referenced = jdbc.queryForObject("SELECT count(*) FROM export_jobs WHERE file_storage_key = :key",
						new MapSqlParameterSource("key", name), Integer.class);
				if ((referenced == null || referenced == 0) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
					deleteQuietly(file);
				}
			}
		} catch (IOException e) {
			log.warn("export temp sweep failed", e);
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			// 다음 정리에서 다시 시도한다.
		}
	}
}
