package com.devgraph.auth.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Family별 최신 rotation row. 만료된 family/refresh token은 활성 기기 목록에서 제외한다. */
@Repository
public class SessionQueryRepository {
	public record SessionRow(UUID id, String deviceLabel, String lastIpPrefix, Instant createdAt,
			Instant lastRotatedAt, Instant absoluteExpiresAt) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	public SessionQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<SessionRow> active(UUID userId) {
		return jdbc.query("""
				SELECT f.id, f.device_label, s.ip_prefix, f.created_at, f.last_rotated_at, f.absolute_expires_at
				FROM auth_session_families f
				JOIN LATERAL (
				  SELECT ip_prefix, expires_at, rotated_at, revoked_at FROM auth_sessions
				  WHERE family_id = f.id ORDER BY created_at DESC, id DESC LIMIT 1
				) s ON true
				WHERE f.user_id = :userId AND f.revoked_at IS NULL AND f.absolute_expires_at > now()
				  AND s.expires_at > now() AND s.rotated_at IS NULL AND s.revoked_at IS NULL
				ORDER BY f.last_rotated_at DESC, f.id ASC
				""", new MapSqlParameterSource("userId", userId), (rs, i) -> new SessionRow(
					rs.getObject("id", UUID.class), rs.getString("device_label"), rs.getString("ip_prefix"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("last_rotated_at", OffsetDateTime.class).toInstant(),
					rs.getObject("absolute_expires_at", OffsetDateTime.class).toInstant()));
	}
}
