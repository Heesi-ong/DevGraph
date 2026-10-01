package com.devgraph.search.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devgraph.common.error.ApiError;
import com.devgraph.common.error.ApiException;
import com.devgraph.common.web.CursorCodec;
import com.devgraph.common.web.PageSize;
import com.devgraph.knowledge.domain.NodeStatus;
import com.devgraph.knowledge.domain.NodeType;
import com.devgraph.search.application.SearchViews.Fallback;
import com.devgraph.search.application.SearchViews.RecentItem;
import com.devgraph.search.application.SearchViews.SearchHit;
import com.devgraph.search.application.SearchViews.SearchPage;
import com.devgraph.search.application.SearchViews.Segment;
import com.devgraph.search.infrastructure.SearchQueryRepository;
import com.devgraph.search.infrastructure.SearchQueryRepository.Criteria;
import com.devgraph.search.infrastructure.SearchQueryRepository.Cursor;
import com.devgraph.search.infrastructure.SearchQueryRepository.Excerpts;
import com.devgraph.search.infrastructure.SearchQueryRepository.Row;
import com.devgraph.workspace.application.WorkspaceQueryService;

/**
 * 설계서 §9.5 통합 검색. 검색어 검증 → Workspace 범위 고정 → 후보·점수 계산(SQL) → 페이지에 오른 항목만
 * 발췌·하이라이트 구간 생성 → 0건이면 fallback 안내.
 * 범위 밖: `scope=snippetHistory`(과거 버전 검색, SRCH-02의 분리 옵션)는 이번 Phase에서 만들지 않았다.
 */
@Service
public class SearchService {

	private static final Logger log = LoggerFactory.getLogger(SearchService.class);
	static final int MIN_QUERY = 2;
	static final int MAX_QUERY = 200;
	static final int DEFAULT_SIZE = 20;
	static final int FALLBACK_SIZE = 5;

	private final SearchQueryRepository repository;
	private final WorkspaceQueryService workspaceQueryService;
	private final MeterRegistry metrics;

	public SearchService(SearchQueryRepository repository, WorkspaceQueryService workspaceQueryService,
			MeterRegistry metrics) {
		this.repository = repository;
		this.workspaceQueryService = workspaceQueryService;
		this.metrics = metrics;
	}

	@Transactional(readOnly = true)
	public SearchPage search(UUID userId, String rawQuery, List<NodeType> types, UUID tagId, String language,
			String framework, boolean includeArchived, String cursor, Integer requestedSize) {
		long started = System.nanoTime();
		String query = requireQuery(rawQuery);
		int size = PageSize.resolve(requestedSize, DEFAULT_SIZE);
		UUID workspaceId = workspaceQueryService.requireWorkspaceId(userId);
		Set<NodeStatus> statuses = includeArchived ? EnumSet.of(NodeStatus.ACTIVE, NodeStatus.ARCHIVED)
				: EnumSet.of(NodeStatus.ACTIVE);
		Criteria criteria = new Criteria(workspaceId, userId, query, statuses.stream().map(Enum::name).toList(),
				types == null ? List.of() : types.stream().distinct().map(Enum::name).toList(), tagId,
				normalizeFilter(language), normalizeFilter(framework));

		List<Row> rows = repository.search(criteria, decodeCursor(cursor), size + 1);
		boolean hasMore = rows.size() > size;
		List<Row> page = hasMore ? rows.subList(0, size) : rows;
		String nextCursor = null;
		if (hasMore) {
			Row last = page.get(page.size() - 1);
			nextCursor = CursorCodec.encode(Long.toString(last.scoreKey()),
					Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, last.updatedAt())), last.id().toString());
		}

		List<String> terms = Highlighter.terms(query);
		Map<UUID, Excerpts> excerpts = repository.excerpts(workspaceId, page.stream().map(Row::id).toList(),
				terms.get(0));
		List<SearchHit> hits = page.stream().map(row -> hit(row, excerpts.get(row.id()), terms)).toList();

		// 0건 안내는 첫 페이지에서만 의미가 있다(다음 페이지가 비었다는 건 정상적인 끝이다).
		Fallback fallback = (hits.isEmpty() && cursor == null) ? fallback(criteria, terms) : null;
		record(hits.size(), started, fallback != null);
		return new SearchPage(hits, nextCursor, hasMore, fallback);
	}

	// ---- helpers ---------------------------------------------------------------------------

	private Fallback fallback(Criteria criteria, List<String> terms) {
		Integer unfiltered = null;
		if (criteria.hasFilters()) {
			int count = repository.count(criteria.withoutFilters());
			unfiltered = count;
		}
		List<SearchHit> similar = repository.similarByTitle(criteria, FALLBACK_SIZE).stream()
				.map(row -> new SearchHit(row.id(), row.type(), row.title(), row.status(), row.scoreKey() / 1_000_000.0,
						List.of("title"), Map.of("title", Highlighter.segments(row.title(), terms, false, false)),
						row.language(), row.framework(), row.favorite(), row.updatedAt()))
				.toList();
		List<RecentItem> recent = similar.isEmpty()
				? repository.recent(criteria.workspaceId(), FALLBACK_SIZE).stream()
						.map(r -> new RecentItem(r.id(), r.type(), r.title(), r.updatedAt())).toList()
				: List.of();
		return new Fallback(unfiltered, similar, recent);
	}

	private static SearchHit hit(Row row, Excerpts excerpts, List<String> terms) {
		List<String> matched = new ArrayList<>();
		Map<String, List<Segment>> highlight = new LinkedHashMap<>();
		highlight.put("title", Highlighter.segments(row.title(), terms, false, false));
		if (row.titleHit()) {
			matched.add("title");
		}
		if (row.tagHit()) {
			matched.add("tag");
		}
		if (row.languageHit()) {
			matched.add("language");
		}
		if (excerpts != null) {
			List<Segment> summary = Highlighter.segments(excerpts.summary(), terms, false, false);
			if (Highlighter.hasMatch(summary)) {
				highlight.put("summary", summary);
			}
			if (row.bodyHit()) {
				matched.add("body");
				highlight.put("body", Highlighter.segments(excerpts.body(), terms, excerpts.bodyStart() > 1,
						excerpts.bodyStart() - 1 + length(excerpts.body()) < excerpts.bodyTotal()));
			}
			if (row.codeHit()) {
				matched.add("code");
				highlight.put("code", Highlighter.segments(excerpts.code(), terms, excerpts.codeStart() > 1,
						excerpts.codeStart() - 1 + length(excerpts.code()) < excerpts.codeTotal()));
			}
		}
		return new SearchHit(row.id(), row.type(), row.title(), row.status(), row.scoreKey() / 1_000_000.0, matched,
				highlight, row.language(), row.framework(), row.favorite(), row.updatedAt());
	}

	private static int length(String text) {
		return text == null ? 0 : text.length();
	}

	/**
	 * 최소 2자(§9.5). 한글 자모만으로 된 입력(조합 중인 "ㅅ", "ㅅㅅ")은 검색어가 아니라 입력 중인 글자이므로
	 * 길이가 충분해도 거부한다. 의미 없는 서버 부하와 무의미한 결과를 피한다.
	 */
	private static String requireQuery(String raw) {
		String query = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
		boolean onlyJamo = !query.isEmpty() && query.chars().allMatch(c -> (c >= 0x3131 && c <= 0x318E) || c == ' ');
		if (query.codePointCount(0, query.length()) < MIN_QUERY || onlyJamo) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "QUERY_TOO_SHORT", "검색어는 2자 이상 입력해 주세요.",
					List.of(new ApiError.FieldError("q", "MIN_" + MIN_QUERY)));
		}
		if (query.length() > MAX_QUERY) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("q", "MAX_" + MAX_QUERY)));
		}
		if (query.indexOf('\u0000') >= 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.",
					List.of(new ApiError.FieldError("q", "INVALID_CHARACTER")));
		}
		return query;
	}

	private static String normalizeFilter(String value) {
		return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private static Cursor decodeCursor(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		List<String> parts = CursorCodec.decode(cursor, 3);
		try {
			return new Cursor(Long.parseLong(parts.get(0)),
					Instant.EPOCH.plus(Long.parseLong(parts.get(1)), ChronoUnit.MICROS), UUID.fromString(parts.get(2)));
		} catch (RuntimeException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "유효하지 않은 cursor입니다.");
		}
	}

	/** 쿼리 지표(§19 "query metrics"). 검색어 자체는 지표·로그에 남기지 않는다(개인 지식이므로 길이와 건수만). */
	private void record(int results, long startedNanos, boolean zeroResult) {
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
		Timer.builder("devgraph.search.duration").tag("result", zeroResult ? "zero" : "hit")
				.register(metrics).record(elapsed);
		if (zeroResult) {
			metrics.counter("devgraph.search.zero_results").increment();
		}
		log.debug("search durationMs={} results={}", elapsed.toMillis(), results);
	}
}
