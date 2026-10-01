package com.devgraph.export.application;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * 설계서 §9.8 Export ZIP을 만든다: `manifest.json`(스키마 버전·앱 버전·파일별 SHA-256), Markdown 본문,
 * Snippet 소스 파일(모든 버전), `relations.json`, `tags.json`, `favorites.json`.
 * 한 Workspace의 데이터만 읽고(§17.1), 휴지통 항목은 넣지 않으며, 파일 이름에는 사용자 입력이 아니라 id만 쓴다
 * (경로 조작 방지). DB는 커서로 흘려 읽어 큰 Workspace도 메모리에 모두 올리지 않는다.
 */
@Component
public class ExportBuilder {

	static final String SCHEMA_VERSION = "1.0";

	/** 한도(MAX_EXPORT_SIZE_MB)를 넘으면 던진다. 입력을 조용히 자르지 않고 실패로 알린다(§21.6). */
	public static class ExportTooLargeException extends RuntimeException {
		public ExportTooLargeException() {
			super("export exceeds size limit");
		}
	}

	public record Result(long sizeBytes, String manifestChecksum) {
	}

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Map<String, String> EXTENSIONS = Map.ofEntries(Map.entry("java", "java"), Map.entry("kotlin", "kt"),
			Map.entry("typescript", "ts"), Map.entry("javascript", "js"), Map.entry("python", "py"), Map.entry("go", "go"),
			Map.entry("rust", "rs"), Map.entry("sql", "sql"), Map.entry("bash", "sh"), Map.entry("yaml", "yaml"),
			Map.entry("json", "json"), Map.entry("html", "html"), Map.entry("css", "css"), Map.entry("c", "c"),
			Map.entry("cpp", "cpp"), Map.entry("csharp", "cs"));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate readOnly;
	private final ExportProperties properties;
	private final String appVersion;

	public ExportBuilder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ExportProperties properties,
			@org.springframework.beans.factory.annotation.Value("${devgraph.app-version:dev}") String appVersion) {
		this.jdbc = jdbc;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.properties = properties;
		this.appVersion = appVersion;
	}

	public Result build(UUID workspaceId, boolean includeArchived, Path target) {
		try {
			return readOnly.execute(status -> write(workspaceId, includeArchived, target));
		} catch (RuntimeException e) {
			try {
				Files.deleteIfExists(target);
			} catch (IOException ignored) {
				// 정리는 만료 배치가 다시 한다.
			}
			throw e;
		}
	}

	private Result write(UUID workspaceId, boolean includeArchived, Path target) {
		String statuses = includeArchived ? "'ACTIVE','ARCHIVED'" : "'ACTIVE'";
		long limit = properties.getMaxSizeMb() * 1024 * 1024;
		List<Map<String, Object>> files = new ArrayList<>();
		int[] counts = new int[4];
		jdbc.setFetchSize(200);
		try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(target));
				ZipOutputStream zip = new ZipOutputStream(raw)) {
			Entries entries = new Entries(zip, files, target, limit);

			jdbc.query(NODE_SQL.formatted(statuses), (ResultSet rs) -> {
				try {
					UUID id = rs.getObject("id", UUID.class);
					String type = rs.getString("node_type");
					entries.add("nodes/" + type.toLowerCase(Locale.ROOT) + "/" + id + ".md", markdown(rs, type));
					counts[0]++;
				} catch (SQLException | IOException e) {
					throw new IllegalStateException(e);
				}
			}, workspaceId);

			jdbc.query("""
					SELECT v.snippet_node_id, v.version_no, v.code, s.language
					FROM snippet_versions v JOIN snippets s ON s.node_id = v.snippet_node_id
					JOIN knowledge_nodes n ON n.id = v.snippet_node_id
					WHERE n.workspace_id = ? AND n.status IN (%s) ORDER BY v.snippet_node_id, v.version_no""".formatted(statuses),
					(ResultSet rs) -> {
						try {
							String ext = EXTENSIONS.getOrDefault(rs.getString("language"), "txt");
							entries.add("snippets/" + rs.getObject("snippet_node_id", UUID.class) + "/v" + rs.getInt("version_no") + "." + ext,
									rs.getString("code").getBytes(StandardCharsets.UTF_8));
							counts[1]++;
						} catch (SQLException | IOException e) {
							throw new IllegalStateException(e);
						}
					}, workspaceId);

			List<Map<String, Object>> relations = jdbc.query("""
					SELECT r.id, r.source_node_id, r.target_node_id, t.key, r.note, r.created_at
					FROM knowledge_relations r JOIN relation_types t ON t.id = r.relation_type_id
					JOIN knowledge_nodes s ON s.id = r.source_node_id JOIN knowledge_nodes d ON d.id = r.target_node_id
					WHERE r.workspace_id = ? AND s.status IN (%s) AND d.status IN (%s) ORDER BY r.created_at, r.id""".formatted(statuses, statuses),
					(rs, i) -> orderedMap("id", rs.getObject("id", UUID.class), "sourceNodeId", rs.getObject("source_node_id", UUID.class),
							"targetNodeId", rs.getObject("target_node_id", UUID.class), "type", rs.getString("key"),
							"note", rs.getString("note"), "createdAt", rs.getTimestamp("created_at").toInstant()), workspaceId);
			counts[2] = relations.size();
			entries.add("relations.json", JSON.writeValueAsBytes(relations));

			List<Map<String, Object>> tags = jdbc.query("SELECT id, name, color FROM tags WHERE workspace_id = ? ORDER BY name",
					(rs, i) -> orderedMap("id", rs.getObject("id", UUID.class), "name", rs.getString("name"), "color", rs.getString("color")),
					workspaceId);
			counts[3] = tags.size();
			entries.add("tags.json", JSON.writeValueAsBytes(tags));

			// 즐겨찾기는 사용자별 상태다. 이 Workspace의 소유자 기준으로 담는다.
			List<UUID> favorites = jdbc.queryForList("""
					SELECT f.node_id FROM favorites f JOIN knowledge_nodes n ON n.id = f.node_id
					WHERE f.workspace_id = ? AND n.status IN (%s) ORDER BY f.node_id""".formatted(statuses), UUID.class, workspaceId);
			entries.add("favorites.json", JSON.writeValueAsBytes(favorites));

			Map<String, Object> manifest = orderedMap("schemaVersion", SCHEMA_VERSION, "generatedByAppVersion", appVersion,
					"generatedAt", Instant.now(), "workspaceId", workspaceId, "includeArchived", includeArchived,
					"counts", orderedMap("nodes", counts[0], "snippetVersions", counts[1], "relations", counts[2], "tags", counts[3]),
					"files", files);
			byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
			entries.addUntracked("manifest.json", manifestBytes);
			zip.finish();
			raw.flush();
			return new Result(Files.size(target), sha256(manifestBytes));
		} catch (IOException e) {
			throw new IllegalStateException("export write failed", e);
		} finally {
			jdbc.setFetchSize(-1);
		}
	}

	/** ZIP 항목을 쓰면서 파일별 SHA-256과 크기를 모으고 크기 한도를 확인한다. */
	private static final class Entries {
		private final ZipOutputStream zip;
		private final List<Map<String, Object>> files;
		private final Path target;
		private final long limit;

		Entries(ZipOutputStream zip, List<Map<String, Object>> files, Path target, long limit) {
			this.zip = zip;
			this.files = files;
			this.target = target;
			this.limit = limit;
		}

		void add(String path, byte[] content) throws IOException {
			addUntracked(path, content);
			files.add(orderedMap("path", path, "sha256", sha256(content), "bytes", content.length));
		}

		void addUntracked(String path, byte[] content) throws IOException {
			zip.putNextEntry(new ZipEntry(path));
			zip.write(content);
			zip.closeEntry();
			zip.flush();
			if (Files.size(target) > limit) {
				throw new ExportTooLargeException();
			}
		}
	}

	private static final String NODE_SQL = """
			SELECT n.id, n.node_type, n.title, n.summary, n.body_md, n.status, n.created_at, n.updated_at,
			  (SELECT coalesce(string_agg(t.name, E'\\u001f' ORDER BY t.name), '') FROM node_tags nt JOIN tags t ON t.id = nt.tag_id WHERE nt.node_id = n.id) AS tag_names,
			  er.error_message, er.environment, er.reproduction_steps_md, er.cause_hypothesis_md, er.resolution_status, er.occurred_at, er.resolved_at,
			  sr.approach_md, sr.steps_md, sr.verification_md, sr.tradeoffs_md, sr.resolved_at AS solution_resolved_at,
			  p.project_status, p.repository_url, p.started_on, p.ended_on,
			  r.url AS resource_url, r.resource_kind, r.site_name,
			  s.language, s.framework, s.current_version_no
			FROM knowledge_nodes n
			LEFT JOIN error_records er ON er.node_id = n.id
			LEFT JOIN solution_records sr ON sr.node_id = n.id
			LEFT JOIN projects p ON p.node_id = n.id
			LEFT JOIN resources r ON r.node_id = n.id
			LEFT JOIN snippets s ON s.node_id = n.id
			WHERE n.workspace_id = ? AND n.status IN (%s) ORDER BY n.created_at, n.id""";

	/** Node 하나의 Markdown: YAML frontmatter(JSON 문자열 표기) + 본문/서브타입 서술. */
	private static byte[] markdown(ResultSet rs, String type) throws SQLException {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("id", rs.getObject("id", UUID.class));
		meta.put("type", type);
		meta.put("title", rs.getString("title"));
		meta.put("status", rs.getString("status"));
		String tagNames = rs.getString("tag_names");
		meta.put("tags", tagNames == null || tagNames.isEmpty() ? List.of() : List.of(tagNames.split("\u001f")));
		meta.put("createdAt", rs.getTimestamp("created_at").toInstant());
		meta.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
		StringBuilder body = new StringBuilder();
		append(body, null, rs.getString("summary"));
		append(body, null, rs.getString("body_md"));
		switch (type) {
			case "SNIPPET" -> {
				meta.put("language", rs.getString("language"));
				meta.put("framework", rs.getString("framework"));
				meta.put("currentVersionNo", rs.getInt("current_version_no"));
				body.append("\n소스 코드는 `snippets/").append(rs.getObject("id", UUID.class)).append("/` 아래 버전별 파일에 있습니다.\n");
			}
			case "ERROR" -> {
				meta.put("resolutionStatus", rs.getString("resolution_status"));
				meta.put("occurredAt", ts(rs, "occurred_at"));
				meta.put("resolvedAt", ts(rs, "resolved_at"));
				meta.put("environment", rs.getString("environment"));
				append(body, "오류 메시지", fence(rs.getString("error_message")));
				append(body, "재현 단계", rs.getString("reproduction_steps_md"));
				append(body, "원인 가설", rs.getString("cause_hypothesis_md"));
			}
			case "SOLUTION" -> {
				meta.put("resolvedAt", ts(rs, "solution_resolved_at"));
				append(body, "접근 방법", rs.getString("approach_md"));
				append(body, "적용 단계", rs.getString("steps_md"));
				append(body, "검증", rs.getString("verification_md"));
				append(body, "트레이드오프", rs.getString("tradeoffs_md"));
			}
			case "PROJECT" -> {
				meta.put("projectStatus", rs.getString("project_status"));
				meta.put("repositoryUrl", rs.getString("repository_url"));
				meta.put("startedOn", date(rs, "started_on"));
				meta.put("endedOn", date(rs, "ended_on"));
			}
			case "RESOURCE" -> {
				meta.put("url", rs.getString("resource_url"));
				meta.put("kind", rs.getString("resource_kind"));
				meta.put("siteName", rs.getString("site_name"));
			}
			default -> {
			}
		}
		StringBuilder out = new StringBuilder("---\n");
		meta.forEach((key, value) -> out.append(key).append(": ").append(JSON.writeValueAsString(value)).append('\n'));
		out.append("---\n\n# ").append(rs.getString("title").replace("\n", " ")).append("\n\n").append(body);
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void append(StringBuilder out, String heading, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		if (heading != null) {
			out.append("## ").append(heading).append("\n\n");
		}
		out.append(text.strip()).append("\n\n");
	}

	/** 메시지 안의 ``` 와 충돌하지 않도록 더 긴 울타리를 쓴다. */
	private static String fence(String text) {
		String fence = "```";
		while (text.contains(fence)) {
			fence += "`";
		}
		return fence + "\n" + text + "\n" + fence;
	}

	private static String date(ResultSet rs, String column) throws SQLException {
		var d = rs.getDate(column);
		return d == null ? "" : d.toLocalDate().toString();
	}

	private static String ts(ResultSet rs, String column) throws SQLException {
		var t = rs.getTimestamp(column);
		return t == null ? "" : t.toInstant().toString();
	}

	private static Map<String, Object> orderedMap(Object... kv) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
