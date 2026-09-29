package com.devgraph.snippet.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.activity.application.ActivityEvent;
import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.CursorCodec;
import com.devgraph.common.web.PageResponse;
import com.devgraph.common.web.PageSize;
import com.devgraph.knowledge.application.NodeCommandService;
import com.devgraph.knowledge.application.NodeDetail;
import com.devgraph.knowledge.application.NodeQueryService;
import com.devgraph.knowledge.application.NodeSummary;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.snippet.application.SnippetViews.SnippetDetail;
import com.devgraph.snippet.application.SnippetViews.SnippetMeta;
import com.devgraph.snippet.application.SnippetViews.SnippetSummary;
import com.devgraph.snippet.application.SnippetViews.VersionContent;
import com.devgraph.snippet.application.SnippetViews.VersionSummary;
import com.devgraph.snippet.domain.SecretScanner;
import com.devgraph.snippet.domain.SecretScanner.Finding;
import com.devgraph.snippet.infrastructure.SnippetJpaEntity;
import com.devgraph.snippet.infrastructure.SnippetQueryRepository;
import com.devgraph.snippet.infrastructure.SnippetRepository;
import com.devgraph.snippet.infrastructure.SnippetVersionJpaEntity;
import com.devgraph.snippet.infrastructure.SnippetVersionRepository;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.3 SNP-01/03/05/06/08/09: Snippet 생성·수정, 불변 버전, 사용 기록, secret 확인 흐름.
 * 공통 필드(제목·요약·태그·상태)는 knowledge 응용 서비스에 맡기고 subtype 고유 데이터만 다룬다.
 * SNP-07(line diff)은 SHOULD이며 "초기에는 버전별 원문 조회만" 허용되므로 이번 Phase에서 제외했다.
 */
@Service
public class SnippetService {

	static final int MAX_CODE_BYTES = 512 * 1024; // §21.6 Snippet 한 버전 512 KB
	private static final int MAX_CHANGE_SUMMARY = 200;
	private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9][a-z0-9+#._-]{0,29}");
	private static final Pattern FRAMEWORK = Pattern.compile("[a-z0-9][a-z0-9+#._ -]{0,49}");

	public static final String CONFIRMED = "CONFIRMED";
	public static final String CONFIRMED_HIGH_RISK = "CONFIRMED_HIGH_RISK";

	private final NodeCommandService nodeCommandService;
	private final NodeQueryService nodeQueryService;
	private final SnippetRepository snippetRepository;
	private final SnippetVersionRepository versionRepository;
	private final SnippetQueryRepository queryRepository;
	private final WorkspaceQueryService workspaceQueryService;
	private final ApplicationEventPublisher events;

	public SnippetService(NodeCommandService nodeCommandService, NodeQueryService nodeQueryService,
			SnippetRepository snippetRepository, SnippetVersionRepository versionRepository,
			SnippetQueryRepository queryRepository, WorkspaceQueryService workspaceQueryService,
			ApplicationEventPublisher events) {
		this.nodeCommandService = nodeCommandService;
		this.nodeQueryService = nodeQueryService;
		this.snippetRepository = snippetRepository;
		this.versionRepository = versionRepository;
		this.queryRepository = queryRepository;
		this.workspaceQueryService = workspaceQueryService;
		this.events = events;
	}

	public record CreateCommand(String title, String summary, String language, String framework, String code,
			String changeSummary, List<UUID> tagIds, String secretConfirmation) {
	}

	public record UpdateCommand(Long version, String title, String summary, String language, String framework,
			String code, String changeSummary, List<UUID> tagIds, String secretConfirmation) {
	}

	@Transactional
	public SnippetDetail create(UUID userId, CreateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		String language = requireLanguage(command.language());
		String framework = optionalFramework(command.framework());
		String code = requireCode(command.code());
		String changeSummary = optionalChangeSummary(command.changeSummary());
		String scanStatus = checkSecrets(code, command.secretConfirmation());

		NodeDetail node = nodeCommandService.createSubtypeNode(userId, NodeType.SNIPPET, command.title(),
				command.summary(), command.tagIds(), "SNIPPET_CREATED");
		snippetRepository.save(new SnippetJpaEntity(node.id(), workspaceId, language, framework, 1, scanStatus));
		SnippetVersionJpaEntity v1 = versionRepository.save(
				new SnippetVersionJpaEntity(workspaceId, node.id(), 1, code, changeSummary, sha256(code), userId));
		SnippetJpaEntity snippet = snippetRepository.findByNodeIdAndWorkspaceId(node.id(), workspaceId).orElseThrow();
		return detail(node, snippet, v1);
	}

	/**
	 * 부분 수정. 코드가 실제로 바뀔 때만 새 버전을 만든다(SNP-05): 같은 코드를 다시 보내거나
	 * 메타데이터만 바꾸면 버전은 늘지 않는다. 어떤 변경이든 Node의 `version`은 올라 동시 수정이 감지된다.
	 */
	@Transactional
	public SnippetDetail update(UUID userId, UUID nodeId, UpdateCommand command) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		SnippetJpaEntity snippet = requireSnippet(workspaceId, nodeId);
		SnippetVersionJpaEntity current = versionRepository
				.findBySnippetNodeIdAndWorkspaceIdAndVersionNo(nodeId, workspaceId, snippet.getCurrentVersionNo())
				.orElseThrow();

		String language = command.language() == null ? snippet.getLanguage() : requireLanguage(command.language());
		String framework = command.framework() == null ? snippet.getFramework()
				: optionalFramework(command.framework());
		boolean metaChanged = !language.equals(snippet.getLanguage())
				|| !java.util.Objects.equals(framework, snippet.getFramework());

		String newCode = null;
		String scanStatus = snippet.getSecretScanStatus();
		if (command.code() != null) {
			String code = requireCode(command.code());
			if (!sha256(code).equals(current.getContentHash())) {
				newCode = code;
				scanStatus = checkSecrets(code, command.secretConfirmation());
			}
		}
		String changeSummary = optionalChangeSummary(command.changeSummary());

		// 공통 필드 검증·낙관적 락은 여기서 일어난다. 실패하면 아래 subtype 쓰기는 실행되지 않는다.
		NodeDetail node = nodeCommandService.updateSubtypeNode(userId, nodeId, command.version(), command.title(),
				command.summary(), command.tagIds(), newCode != null || metaChanged, "SNIPPET_UPDATED");

		if (metaChanged) {
			snippet.changeLanguage(language, framework);
		}
		SnippetVersionJpaEntity shown = current;
		if (newCode != null) {
			snippet.advanceVersion(scanStatus);
			shown = versionRepository.save(new SnippetVersionJpaEntity(workspaceId, nodeId,
					snippet.getCurrentVersionNo(), newCode, changeSummary, sha256(newCode), userId));
			events.publishEvent(new ActivityEvent(workspaceId, userId, "SNIPPET_VERSION_CREATED", "NODE", nodeId));
		}
		return detail(node, snippet, shown);
	}

	@Transactional(readOnly = true)
	public SnippetDetail get(UUID userId, UUID nodeId) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		NodeDetail node = nodeQueryService.getOfType(userId, nodeId, NodeType.SNIPPET);
		SnippetJpaEntity snippet = requireSnippet(workspaceId, nodeId);
		SnippetVersionJpaEntity current = versionRepository
				.findBySnippetNodeIdAndWorkspaceIdAndVersionNo(nodeId, workspaceId, snippet.getCurrentVersionNo())
				.orElseThrow();
		return detail(node, snippet, current);
	}

	@Transactional(readOnly = true)
	public PageResponse<SnippetSummary> list(UUID userId, String language, String framework, UUID tagId,
			NodeStatus status, Boolean favorite, String sort, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		int size = PageSize.resolve(requestedSize);
		if (sort != null && !sort.equals("updatedAt")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("sort", "UNSUPPORTED_SORT")));
		}
		Instant afterUpdatedAt = null;
		UUID afterId = null;
		if (cursor != null && !cursor.isBlank()) {
			List<String> parts = CursorCodec.decode(cursor, 2);
			try {
				afterUpdatedAt = Instant.EPOCH.plus(Long.parseLong(parts.get(0)), ChronoUnit.MICROS);
				afterId = UUID.fromString(parts.get(1));
			} catch (RuntimeException e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
			}
		}

		List<SnippetQueryRepository.Row> rows = queryRepository.search(workspaceId, userId,
				status == null ? NodeStatus.ACTIVE : status, normalizeFilter(language), normalizeFilter(framework),
				tagId, Boolean.TRUE.equals(favorite), afterUpdatedAt, afterId, size + 1);
		boolean hasMore = rows.size() > size;
		List<SnippetQueryRepository.Row> page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasMore) {
			SnippetQueryRepository.Row last = page.get(page.size() - 1);
			nextCursor = CursorCodec.encode(
					Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, last.updatedAt())), last.nodeId().toString());
		}

		List<UUID> ids = page.stream().map(SnippetQueryRepository.Row::nodeId).toList();
		Map<UUID, SnippetJpaEntity> snippets = snippetRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(SnippetJpaEntity::getNodeId, Function.identity()));
		List<SnippetSummary> items = nodeQueryService.summariesInOrder(userId, ids).stream()
				.map(n -> summary(n, snippets.get(n.id())))
				.toList();
		return new PageResponse<>(items, nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public PageResponse<VersionSummary> listVersions(UUID userId, UUID nodeId, String cursor, Integer requestedSize) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		requireSnippet(workspaceId, nodeId);
		int size = PageSize.resolve(requestedSize);
		Integer after = null;
		if (cursor != null && !cursor.isBlank()) {
			try {
				after = Integer.parseInt(CursorCodec.decode(cursor, 1).get(0));
			} catch (NumberFormatException e) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
			}
		}
		var rows = versionRepository.findRows(nodeId, workspaceId, after, PageRequest.of(0, size + 1));
		boolean hasMore = rows.size() > size;
		var page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = hasMore ? CursorCodec.encode(Integer.toString(page.get(page.size() - 1).versionNo())) : null;
		return new PageResponse<>(page.stream()
				.map(r -> new VersionSummary(r.versionNo(), r.changeSummary(), r.createdAt(), r.codeLength()))
				.toList(), nextCursor, hasMore);
	}

	@Transactional(readOnly = true)
	public VersionContent getVersion(UUID userId, UUID nodeId, int versionNo) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		requireSnippet(workspaceId, nodeId);
		return versionRepository.findBySnippetNodeIdAndWorkspaceIdAndVersionNo(nodeId, workspaceId, versionNo)
				.map(v -> new VersionContent(v.getVersionNo(), v.getCode(), v.getChangeSummary(), v.getCreatedAt()))
				.orElseThrow(SnippetService::notFound);
	}

	/** SNP-08: 복사 통계. 실패해도 복사 자체를 막지 않는다는 계약은 클라이언트가 지킨다(§14.4). */
	@Transactional
	public void recordUse(UUID userId, UUID nodeId, String action) {
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		if (!"COPY".equals(action)) {
			throw validation("action", "UNSUPPORTED_ACTION");
		}
		if (snippetRepository.recordUse(nodeId, workspaceId) == 0) {
			throw notFound();
		}
	}

	// ---- validation ------------------------------------------------------------------------

	private static String requireLanguage(String value) {
		String language = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
		if (!LANGUAGE.matcher(language).matches()) {
			throw validation("language", "INVALID_LANGUAGE");
		}
		return language;
	}

	/** 빈 문자열은 "지움"이다. null이면 호출자가 변경 없음으로 처리한다. */
	private static String optionalFramework(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String framework = value.trim().toLowerCase(java.util.Locale.ROOT);
		if (!FRAMEWORK.matcher(framework).matches()) {
			throw validation("framework", "INVALID_FRAMEWORK");
		}
		return framework;
	}

	private static String normalizeFilter(String value) {
		return value == null || value.isBlank() ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static String optionalChangeSummary(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() > MAX_CHANGE_SUMMARY || trimmed.indexOf('\u0000') >= 0) {
			throw validation("changeSummary", "MAX_" + MAX_CHANGE_SUMMARY);
		}
		return trimmed;
	}

	/**
	 * 코드는 **있는 그대로** 저장한다: trim·줄바꿈 정규화를 하지 않는다(정확한 복사, SNP-03).
	 * PostgreSQL이 저장할 수 없는 NUL과 짝 없는 surrogate는 조용히 바뀌어 저장되지 않도록 거부한다.
	 */
	private static String requireCode(String code) {
		if (code == null || code.isBlank()) {
			throw validation("code", "REQUIRED");
		}
		if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "코드가 너무 큽니다.",
					List.of(new ApiError.FieldError("code", "MAX_512KB")));
		}
		for (int i = 0; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == '\u0000') {
				throw validation("code", "INVALID_CHARACTER");
			}
			if (Character.isHighSurrogate(c)) {
				if (i + 1 >= code.length() || !Character.isLowSurrogate(code.charAt(i + 1))) {
					throw validation("code", "INVALID_CHARACTER");
				}
				i++;
			} else if (Character.isLowSurrogate(c)) {
				throw validation("code", "INVALID_CHARACTER");
			}
		}
		return code;
	}

	/**
	 * §17.5: 의심 패턴이 있으면 저장 전에 확인을 요구한다(차단이 아니라 확인+수정 경로).
	 * 일반 의심은 `CONFIRMED`, private key는 `CONFIRMED_HIGH_RISK`(재확인)여야 통과한다.
	 * 응답에는 종류와 줄 번호만 담고 의심 값은 담지 않는다.
	 */
	private static String checkSecrets(String code, String confirmation) {
		List<Finding> findings = SecretScanner.scan(code);
		if (findings.isEmpty()) {
			return "CLEAN";
		}
		boolean high = SecretScanner.hasHighRisk(findings);
		boolean confirmed = CONFIRMED_HIGH_RISK.equals(confirmation) || (!high && CONFIRMED.equals(confirmation));
		if (!confirmed) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SECRET_CONFIRMATION_REQUIRED",
					high ? "private key가 포함된 것 같습니다. 저장하려면 위험을 재확인해야 합니다."
							: "secret으로 보이는 값이 포함된 것 같습니다. 수정하거나 확인 후 저장해 주세요.",
					findings.stream()
							.map(f -> new ApiError.FieldError("code", f.kind().name() + "@L" + f.line()))
							.toList());
		}
		return "CONFIRMED_WITH_FINDINGS";
	}

	// ---- assembly --------------------------------------------------------------------------

	private SnippetJpaEntity requireSnippet(UUID workspaceId, UUID nodeId) {
		return snippetRepository.findByNodeIdAndWorkspaceId(nodeId, workspaceId).orElseThrow(SnippetService::notFound);
	}

	private static SnippetDetail detail(NodeDetail node, SnippetJpaEntity snippet, SnippetVersionJpaEntity shown) {
		return new SnippetDetail(node.id(), node.type(), node.title(), node.summary(), node.status(), node.version(),
				node.tags(), node.favorite(), node.createdAt(), node.updatedAt(),
				new SnippetMeta(snippet.getLanguage(), snippet.getFramework(), snippet.getCurrentVersionNo(),
						snippet.getUseCount(), snippet.getLastUsedAt(), snippet.getSecretScanStatus()),
				new VersionContent(shown.getVersionNo(), shown.getCode(), shown.getChangeSummary(),
						shown.getCreatedAt()),
				node.relations());
	}

	private static SnippetSummary summary(NodeSummary node, SnippetJpaEntity snippet) {
		return new SnippetSummary(node.id(), node.title(), node.summary(), node.status(), node.version(), node.tags(),
				node.favorite(), node.createdAt(), node.updatedAt(), snippet.getLanguage(), snippet.getFramework(),
				snippet.getCurrentVersionNo(), snippet.getUseCount(), snippet.getLastUsedAt());
	}

	private static String sha256(String code) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "항목을 찾을 수 없습니다.");
	}

	private static ApiException validation(String field, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
				List.of(new ApiError.FieldError(field, reason)));
	}
}
