package com.devgraph.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devgraph.export.application.ExportProcessor;
import com.devgraph.export.application.ExportProperties;
import com.devgraph.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 설계서 §9.8 Export: 재인증·동시 1개, 비동기 처리, ZIP 내용과 manifest 무결성, 일회성 다운로드, 만료·재시도(최대 2회),
 * 정리 배치, Workspace 격리. 완료 조건의 "관계가 손실 없이 포함"(§27.1 14번)을 여기서 검증한다.
 */
class ExportIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "correct-horse-battery";

	@Autowired
	JdbcTemplate jdbc;

	@org.junit.jupiter.api.BeforeEach
	void emptyQueue() {
		// 폴러 대기열은 전역이라 다른 테스트가 남긴 PENDING job을 집어가지 않게 비운다.
		jdbc.update("delete from export_jobs");
	}
	@Autowired
	ExportProcessor processor;
	@Autowired
	ExportProperties properties;
	@Autowired
	MeterRegistry metrics;

	record Device(String accessToken) {

		UUID userId() {
			return UUID.fromString(claim(accessToken, "sub"));
		}
	}

	// ---- content and integrity --------------------------------------------------------------

	@Test
	void exportContainsEverythingWithRelationsAndAVerifiableManifest() throws Exception {
		Device device = signup();
		Device stranger = signup();
		call(HttpMethod.POST, "/api/v1/nodes", stranger, Map.of("type", "CONCEPT", "title", "STRANGER SECRET"));

		String tag = JsonPath.read(call(HttpMethod.POST, "/api/v1/tags", device, Map.of("name", "backend")).getBody(), "$.id");
		String concept = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "CONCEPT", "title", "JPA \"basics\"",
				"summary", "요약입니다", "bodyMd", "# 본문\n내용 --- 이 줄은 구분선처럼 보인다", "tagIds", List.of(tag))).getBody(), "$.id");
		String note = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "NOTE", "title", "Note", "bodyMd", "n")).getBody(), "$.id");
		String crlfCode = "line1\r\n\tline2 한글 😀\r\n";
		String snippet = JsonPath.read(call(HttpMethod.POST, "/api/v1/snippets", device,
				Map.of("title", "Repo", "language", "java", "code", crlfCode)).getBody(), "$.id");
		call(HttpMethod.PATCH, "/api/v1/snippets/" + snippet, device, Map.of("version", 0, "code", "class V2 {}"));
		String error = JsonPath.read(call(HttpMethod.POST, "/api/v1/errors", device, Map.of("title", "Lazy", "errorMessage",
				"org.hibernate.LazyInitializationException: no Session ``` with fence")).getBody(), "$.id");
		String solution = JsonPath.read(call(HttpMethod.POST, "/api/v1/solutions", device, Map.of("title", "Fix", "approachMd", "fetch join",
				"errorNodeId", error)).getBody(), "$.id");
		String project = JsonPath.read(call(HttpMethod.POST, "/api/v1/projects", device, Map.of("title", "TeamFlow",
				"repositoryUrl", "https://github.com/me/t", "startedOn", "2026-01-02")).getBody(), "$.id");
		String resource = JsonPath.read(call(HttpMethod.POST, "/api/v1/resources", device, Map.of("title", "Doc", "url", "https://example.com/doc")).getBody(), "$.id");
		relate(device, snippet, concept, "IS_EXAMPLE_OF");
		relate(device, solution, project, "APPLIED_IN");
		relate(device, concept, note, "RELATED_TO");
		call(HttpMethod.PUT, "/api/v1/nodes/" + concept + "/favorite", device, null);
		String archived = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "NOTE", "title", "Archived one")).getBody(), "$.id");
		call(HttpMethod.POST, "/api/v1/nodes/" + archived + "/archive", device, Map.of("version", 0));
		String trashed = JsonPath.read(call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "NOTE", "title", "Trashed one")).getBody(), "$.id");
		relate(device, trashed, note, "RELATED_TO");
		call(HttpMethod.POST, "/api/v1/nodes/" + trashed + "/trash", device, Map.of("version", 0));
		String hugeBody = "word ".repeat(180_000); // 약 900KB
		call(HttpMethod.POST, "/api/v1/nodes", device, Map.of("type", "NOTE", "title", "Huge", "bodyMd", hugeBody));

		Map<String, byte[]> zip = download(device, runExport(device, false));

		// manifest: 스키마 버전, 앱 버전, 파일별 checksum이 실제 내용과 일치한다.
		String manifest = new String(zip.get("manifest.json"), StandardCharsets.UTF_8);
		assertThat((String) JsonPath.read(manifest, "$.schemaVersion")).isEqualTo("1.0");
		assertThat((String) JsonPath.read(manifest, "$.generatedByAppVersion")).isNotBlank();
		assertThat((Boolean) JsonPath.read(manifest, "$.includeArchived")).isFalse();
		List<String> listed = JsonPath.read(manifest, "$.files[*].path");
		List<String> sums = JsonPath.read(manifest, "$.files[*].sha256");
		assertThat(listed).hasSize(sums.size()).doesNotContain("manifest.json");
		assertThat(zip.keySet()).containsAll(listed).contains("manifest.json");
		for (int i = 0; i < listed.size(); i++) {
			assertThat(sha256(zip.get(listed.get(i)))).as(listed.get(i)).isEqualTo(sums.get(i));
		}
		assertThat(jdbc.queryForObject("select manifest_checksum from export_jobs where requested_by = ?", String.class, device.userId()))
				.isEqualTo(sha256(zip.get("manifest.json")));

		// 내용: 휴지통·보관·다른 사용자 데이터는 없고, 나머지 7종 Node는 모두 있다.
		String all = String.join("\n", zip.values().stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList());
		assertThat(all).doesNotContain("STRANGER SECRET").doesNotContain("Trashed one").doesNotContain("Archived one");
		assertThat(zip.keySet()).contains("nodes/concept/" + concept + ".md", "nodes/note/" + note + ".md",
				"nodes/snippet/" + snippet + ".md", "nodes/error/" + error + ".md", "nodes/solution/" + solution + ".md",
				"nodes/project/" + project + ".md", "nodes/resource/" + resource + ".md");
		assertThat((Integer) JsonPath.read(manifest, "$.counts.nodes")).isEqualTo(8); // 7종 + Huge

		String conceptMd = text(zip, "nodes/concept/" + concept + ".md");
		assertThat(conceptMd).startsWith("---\n").contains("title: \"JPA \\\"basics\\\"\"").contains("\"backend\"")
				.contains("# 본문\n내용 --- 이 줄은 구분선처럼 보인다").contains("요약입니다");
		// 즉 frontmatter는 첫 --- ... --- 한 쌍이고 본문 안의 '---'는 본문일 뿐이다.
		// Snippet 소스는 모든 버전이 원문 그대로(줄바꿈·탭·이모지까지) 들어간다.
		assertThat(text(zip, "snippets/" + snippet + "/v1.java")).isEqualTo(crlfCode);
		assertThat(text(zip, "snippets/" + snippet + "/v2.java")).isEqualTo("class V2 {}");
		assertThat((Integer) JsonPath.read(manifest, "$.counts.snippetVersions")).isEqualTo(2);
		// Error 메시지는 본문의 ``` 와 충돌하지 않는 울타리로 감싼다.
		assertThat(text(zip, "nodes/error/" + error + ".md")).contains("````\norg.hibernate.LazyInitializationException: no Session ``` with fence\n````");
		assertThat(text(zip, "nodes/project/" + project + ".md")).contains("repositoryUrl: \"https://github.com/me/t\"").contains("startedOn: \"2026-01-02\"");
		assertThat(text(zip, "nodes/resource/" + resource + ".md")).contains("url: \"https://example.com/doc\"");
		assertThat(text(zip, "nodes/solution/" + solution + ".md")).contains("## 접근 방법").contains("fetch join");

		// 관계: 양 끝이 모두 포함된 것만, 타입 key와 방향 그대로. 휴지통 Node와 이어진 관계는 빠진다.
		String relations = text(zip, "relations.json");
		assertThat(JsonPath.<List<String>>read(relations, "$[*].type")).containsExactlyInAnyOrder("IS_EXAMPLE_OF", "APPLIED_IN", "RELATED_TO",
				"SOLVED_BY");
		assertThat(JsonPath.<List<String>>read(relations, "$[?(@.type=='APPLIED_IN')].sourceNodeId")).containsExactly(solution);
		assertThat(JsonPath.<List<String>>read(relations, "$[?(@.type=='APPLIED_IN')].targetNodeId")).containsExactly(project);
		assertThat(JsonPath.<List<String>>read(text(zip, "favorites.json"), "$")).containsExactly(concept);
		assertThat(JsonPath.<List<String>>read(text(zip, "tags.json"), "$[*].name")).containsExactly("backend");

		// 보관 항목은 옵션을 켜면 포함된다.
		Map<String, byte[]> withArchived = download(device, runExport(device, true));
		assertThat(withArchived.keySet()).contains("nodes/note/" + archived + ".md");
		assertThat((Boolean) JsonPath.read(text(withArchived, "manifest.json"), "$.includeArchived")).isTrue();
		assertThat(String.join("\n", withArchived.values().stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList()))
				.doesNotContain("Trashed one").doesNotContain("STRANGER SECRET");
	}

	// ---- API contract -----------------------------------------------------------------------

	@Test
	void creationNeedsAReauthTokenAndOnlyOneJobMayBeActive() {
		Device device = signup();
		assertThat(call(HttpMethod.POST, "/api/v1/exports", device, Map.of()).getBody()).contains("REAUTH_REQUIRED");
		assertThat(createExport(device, reauthToken(device, "ACCOUNT_DELETE_REQUEST", null), false).getStatusCode())
				.isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN); // 다른 purpose/대상의 토큰
		assertThat(createExport(device, reauthToken(device, "EXPORT_CREATE", null), false, "TAR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		String token = reauthToken(device, "EXPORT_CREATE", null);
		var created = createExport(device, token, false);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat((String) JsonPath.read(created.getBody(), "$.status")).isEqualTo("PENDING");
		String jobId = JsonPath.read(created.getBody(), "$.jobId");

		// 진행 중에는 새로 만들 수 없다(409). 이 실패는 재인증 토큰을 소진하지 않는다.
		String second = reauthToken(device, "EXPORT_CREATE", null);
		var conflict = createExport(device, second, false);
		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(conflict.getBody()).contains("EXPORT_IN_PROGRESS");
		assertThat(status(device, jobId)).isEqualTo("PENDING");
		assertThat(processor.processNext()).isTrue();
		assertThat(status(device, jobId)).isEqualTo("COMPLETED");
		assertThat(createExport(device, second, false).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // 같은 토큰이 아직 유효했다
		assertThat(processor.processNext()).isTrue();
		assertThat(processor.processNext()).isFalse(); // 대기열이 비었다
	}

	@Test
	void concurrentCreationsFromTwoDevicesYieldExactlyOneJob() throws Exception {
		String email = "exp-" + UUID.randomUUID() + "@example.com";
		Device first = signup(email);
		Device second = login(email);
		String token1 = reauthToken(first, "EXPORT_CREATE", null);
		String token2 = reauthToken(second, "EXPORT_CREATE", null);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (var pair : List.of(Map.entry(first, token1), Map.entry(second, token2))) {
			results.add(pool.submit(() -> {
				start.await();
				return createExport(pair.getKey(), pair.getValue(), false).getStatusCode().value();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (var f : results) {
			statuses.add(f.get());
		}
		pool.shutdown();
		assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		assertThat(jdbc.queryForObject("select count(*) from export_jobs where requested_by = ?", Integer.class, first.userId())).isEqualTo(1);
	}

	@Test
	void jobsAreVisibleOnlyToTheirWorkspaceAndDownloadTokensAreSingleUseAndShortLived() throws Exception {
		Device owner = signup();
		Device other = signup();
		String jobId = runExport(owner, false);

		assertThat(call(HttpMethod.GET, "/api/v1/exports/" + jobId, other, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(JsonPath.<List<?>>read(call(HttpMethod.GET, "/api/v1/exports", other, null).getBody(), "$")).isEmpty();
		assertThat(JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/exports", owner, null).getBody(), "$[*].jobId")).containsExactly(jobId);

		String firstUrl = downloadUrl(owner, jobId);
		String secondUrl = downloadUrl(owner, jobId); // 조회할 때마다 새 token을 발급하고 이전 것은 무효가 된다.
		assertThat(fetch(firstUrl).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetch(withToken(secondUrl, "wrong-token")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetch(secondUrl.substring(0, secondUrl.indexOf("?token="))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// 만료된 token
		jdbc.update("update export_jobs set download_token_expires_at = now() - interval '1 minute' where id = ?", UUID.fromString(jobId));
		assertThat(fetch(secondUrl).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		String freshUrl = downloadUrl(owner, jobId);
		var ok = fetchBytes(freshUrl);
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ok.getHeaders().getContentType().toString()).isEqualTo("application/zip");
		assertThat(ok.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment; filename=\"devgraph-export-");
		assertThat(ok.getHeaders().getCacheControl()).contains("no-store");
		assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		// 한 번 받으면 끝: 파일이 지워지고 job은 EXPIRED.
		assertThat(fetch(freshUrl).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(call(HttpMethod.GET, "/api/v1/exports/" + jobId, owner, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
		assertThat(Files.list(Path.of(properties.getTempDir())).filter(p -> p.toString().endsWith(".zip")
				&& jdbc.queryForObject("select count(*) from export_jobs where file_storage_key = ?", Integer.class, p.getFileName().toString()) > 0))
				.isEmpty();
		// 다운로드는 감사 로그에 남는다(토큰 값은 없다).
		assertThat(jdbc.queryForList("select coalesce(metadata::text,'') from security_audit_logs where actor_user_id = ? and event_type = 'EXPORT_DOWNLOAD'",
				String.class, owner.userId())).hasSize(1).noneMatch(m -> m.contains("token"));
	}

	// ---- failure handling ---------------------------------------------------------------------

	@Test
	void failedJobsRetryTwiceThenFailWithAGenericReasonAndTooLargeFailsImmediately() throws Exception {
		Device device = signup();
		double failedBefore = metrics.counter("devgraph.export.failed", "reason", "EXPORT_FAILED").count();
		String original = properties.getTempDir();
		try {
			properties.setTempDir("/dev/null/devgraph-export"); // 디렉터리를 만들 수 없어 매번 실패한다.
			var created = createExport(device, reauthToken(device, "EXPORT_CREATE", null), false);
			String jobId = JsonPath.read(created.getBody(), "$.jobId");
			assertThat(processor.processNext()).isTrue();
			assertThat(row(jobId)).containsEntry("status", "PENDING").containsEntry("retry_count", 1);
			assertThat(processor.processNext()).isTrue();
			assertThat(row(jobId)).containsEntry("status", "PENDING").containsEntry("retry_count", 2);
			assertThat(processor.processNext()).isTrue(); // 세 번째(두 번째 재시도)도 실패 → 확정
			assertThat(row(jobId)).containsEntry("status", "FAILED").containsEntry("failure_reason", "EXPORT_FAILED");
			assertThat(processor.processNext()).isFalse();
			// 사용자에게는 일반화된 사유만 보인다(경로·예외 메시지 없음).
			var view = call(HttpMethod.GET, "/api/v1/exports/" + jobId, device, null);
			assertThat((String) JsonPath.read(view.getBody(), "$.failureReason")).isEqualTo("EXPORT_FAILED");
			assertThat(view.getBody()).doesNotContain("/dev/null").doesNotContain("Exception");
			assertThat(metrics.counter("devgraph.export.failed", "reason", "EXPORT_FAILED").count()).isEqualTo(failedBefore + 1);
			// 실패하면 새로 요청할 수 있다.
			assertThat(createExport(device, reauthToken(device, "EXPORT_CREATE", null), false).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
			properties.setTempDir(original);
			assertThat(processor.processNext()).isTrue();

			// 크기 한도 초과: 재시도해도 같으므로 바로 확정한다.
			long originalLimit = properties.getMaxSizeMb();
			try {
				Set<String> filesBefore = listing(original);
				properties.setMaxSizeMb(0);
				String big = JsonPath.read(createExport(device, reauthToken(device, "EXPORT_CREATE", null), false).getBody(), "$.jobId");
				assertThat(processor.processNext()).isTrue();
				assertThat(row(big)).containsEntry("status", "FAILED").containsEntry("failure_reason", "EXPORT_TOO_LARGE").containsEntry("retry_count", 0);
				assertThat(listing(original)).isEqualTo(filesBefore); // 남은 부분 파일이 없다
			} finally {
				properties.setMaxSizeMb(originalLimit);
			}
		} finally {
			properties.setTempDir(original);
		}
	}

	@Test
	void cleanupExpiresOldFilesRecoversStuckJobsAndSweepsOrphans() throws Exception {
		Device device = signup();
		String jobId = runExport(device, false);
		Path dir = Path.of(properties.getTempDir());
		String key = jdbc.queryForObject("select file_storage_key from export_jobs where id = ?", String.class, UUID.fromString(jobId));
		assertThat(Files.exists(dir.resolve(key))).isTrue();

		// 보관 기한 전에는 그대로
		processor.cleanup(Instant.now());
		assertThat(Files.exists(dir.resolve(key))).isTrue();
		jdbc.update("update export_jobs set expires_at = now() - interval '1 minute' where id = ?", UUID.fromString(jobId));
		assertThat(call(HttpMethod.GET, "/api/v1/exports/" + jobId, device, null).getStatusCode()).isEqualTo(HttpStatus.GONE); // 배치 전에도 만료로 본다
		processor.cleanup(Instant.now());
		assertThat(Files.exists(dir.resolve(key))).isFalse();
		assertThat(row(jobId)).containsEntry("status", "EXPIRED");

		// 멈춘 PROCESSING은 대기열로 돌아오고, 재시도 한도를 넘으면 FAILED가 된다.
		String stuck = JsonPath.read(createExport(device, reauthToken(device, "EXPORT_CREATE", null), false).getBody(), "$.jobId");
		jdbc.update("update export_jobs set status = 'PROCESSING', started_at = now() - interval '1 hour' where id = ?", UUID.fromString(stuck));
		processor.cleanup(Instant.now());
		assertThat(row(stuck)).containsEntry("status", "PENDING").containsEntry("retry_count", 1);
		jdbc.update("update export_jobs set status = 'PROCESSING', started_at = now() - interval '1 hour', retry_count = 2 where id = ?", UUID.fromString(stuck));
		processor.cleanup(Instant.now());
		assertThat(row(stuck)).containsEntry("status", "FAILED");

		// 어떤 job도 가리키지 않는 오래된 파일만 지운다.
		Path orphan = Files.writeString(dir.resolve("orphan-leftover.part"), "x");
		Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3 * 3600)));
		Path recent = Files.writeString(dir.resolve("recent-leftover.part"), "x");
		processor.cleanup(Instant.now());
		assertThat(Files.exists(orphan)).isFalse();
		assertThat(Files.exists(recent)).isTrue();
		Files.deleteIfExists(recent);
	}

	// ---- helpers ----------------------------------------------------------------------------

	/** 재인증 → 생성 → 폴러 1회 처리 → 완료 확인. job id를 돌려준다. */
	private String runExport(Device device, boolean includeArchived) {
		var created = createExport(device, reauthToken(device, "EXPORT_CREATE", null), includeArchived);
		assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.ACCEPTED);
		String jobId = JsonPath.read(created.getBody(), "$.jobId");
		assertThat(processor.processNext()).isTrue();
		assertThat(status(device, jobId)).isEqualTo("COMPLETED");
		return jobId;
	}

	private Map<String, byte[]> download(Device device, String jobId) throws Exception {
		var response = fetchBytes(downloadUrl(device, jobId));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.getBody()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				assertThat(entry.getName()).doesNotContain("..").doesNotStartWith("/");
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	private String downloadUrl(Device device, String jobId) {
		var view = call(HttpMethod.GET, "/api/v1/exports/" + jobId, device, null);
		assertThat(view.getStatusCode()).as(view.getBody()).isEqualTo(HttpStatus.OK);
		return JsonPath.read(view.getBody(), "$.downloadUrl");
	}

	/** 응답의 URL은 설정된 공개 주소를 쓰므로, 경로와 query만 떼어 테스트 서버 주소로 연다. */
	private ResponseEntity<String> fetch(String url) {
		return restTemplate.getForEntity(URI.create(baseUrl(url.substring(url.indexOf("/api/")))), String.class);
	}

	private ResponseEntity<byte[]> fetchBytes(String url) {
		return restTemplate.getForEntity(URI.create(baseUrl(url.substring(url.indexOf("/api/")))), byte[].class);
	}

	private static String withToken(String url, String token) {
		return url.substring(0, url.indexOf("?token=")) + "?token=" + token;
	}

	private String status(Device device, String jobId) {
		return JsonPath.read(call(HttpMethod.GET, "/api/v1/exports/" + jobId, device, null).getBody(), "$.status");
	}

	private static Set<String> listing(String dir) throws Exception {
		try (var files = Files.list(Path.of(dir))) {
			return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
	}

	private Map<String, Object> row(String jobId) {
		return jdbc.queryForMap("select status, retry_count, failure_reason from export_jobs where id = ?", UUID.fromString(jobId));
	}

	private ResponseEntity<String> createExport(Device device, String reauthToken, boolean includeArchived) {
		return createExport(device, reauthToken, includeArchived, "ZIP");
	}

	private ResponseEntity<String> createExport(Device device, String reauthToken, boolean includeArchived, String format) {
		HttpHeaders headers = bearer(device);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.add("X-Reauth-Token", reauthToken);
		return restTemplate.exchange(URI.create(baseUrl("/api/v1/exports")), HttpMethod.POST,
				new HttpEntity<>(Map.of("includeArchived", includeArchived, "format", format), headers), String.class);
	}

	private String reauthToken(Device device, String purpose, UUID targetId) {
		Map<String, Object> body = new HashMap<>(Map.of("password", PASSWORD, "purpose", purpose));
		if (targetId != null) {
			body.put("targetId", targetId.toString());
		}
		var response = call(HttpMethod.POST, "/api/v1/auth/reauth", device, body);
		return response.getStatusCode() == HttpStatus.OK ? JsonPath.read(response.getBody(), "$.reauthToken") : "no-token";
	}

	private void relate(Device device, String source, String target, String typeKey) {
		String typeId = JsonPath.<List<String>>read(call(HttpMethod.GET, "/api/v1/relation-types", device, null).getBody(),
				"$[?(@.key=='" + typeKey + "')].id").get(0);
		var response = call(HttpMethod.POST, "/api/v1/relations", device,
				Map.of("sourceNodeId", source, "targetNodeId", target, "relationTypeId", typeId));
		assertThat(response.getStatusCode()).as(typeKey + " " + response.getBody()).isEqualTo(HttpStatus.CREATED);
	}

	private static String text(Map<String, byte[]> zip, String path) {
		assertThat(zip).containsKey(path);
		return new String(zip.get(path), StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private HttpHeaders bearer(Device device) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(device.accessToken());
		return headers;
	}

	private ResponseEntity<String> call(HttpMethod method, String path, Device device, Object body) {
		HttpHeaders headers = bearer(device);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		return restTemplate.exchange(URI.create(baseUrl(path)), method, new HttpEntity<>(body, headers), String.class);
	}

	private Device signup() {
		return signup("exp-" + UUID.randomUUID() + "@example.com");
	}

	private Device signup(String email) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/signup"),
				new HttpEntity<>(Map.of("email", email, "displayName", "Export Tester", "password", PASSWORD), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return new Device(JsonPath.read(response.getBody(), "$.accessToken"));
	}

	private Device login(String email) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		var response = restTemplate.postForEntity(baseUrl("/api/v1/auth/login"),
				new HttpEntity<>(Map.of("email", email, "password", PASSWORD), headers), String.class);
		return new Device(JsonPath.read(response.getBody(), "$.accessToken"));
	}

	private static String claim(String jwt, String name) {
		String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
		return JsonPath.read(payload, "$." + name);
	}
}
