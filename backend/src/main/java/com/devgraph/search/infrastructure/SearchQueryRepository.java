package com.devgraph.search.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.devgraph.common.web.LikeEscape;
import com.devgraph.search.application.SearchRanking;

/**
 * 검색 전용 읽기 쿼리(PostgreSQL FTS + pg_trgm). 검색은 여러 모듈의 테이블을 한 번에 읽는 read model이라
 * 다른 모듈의 JPA 클래스가 아니라 SQL로 직접 조회한다. 값은 모두 바인드 파라미터로만 넣는다(SQL 조각은 이 클래스의 상수뿐).
 *
 * <p>쿼리 형태(§9.5): Workspace로 범위를 먼저 고정 → title exact/prefix/contains, tag exact, FTS, code 부분 일치를
 * 합쳐 후보를 고르고 → 같은 척도의 점수로 정렬 `(score_key DESC, updated_at DESC, id ASC)`.
 * 점수는 `round(score * 1_000_000)::bigint`(score_key)로만 비교한다(부동소수점 cursor 문제, §9.5).
	 * 최신성은 하루 단위로 감쇠하되 첫 페이지의 rankingAt을 cursor로 전달한다.
	 * 날짜 경계를 넘어도 같은 검색의 최신성 점수는 고정된다(데이터 변경의 snapshot 보장은 별개).
 */
@Repository
public class SearchQueryRepository {

	/** 검색 조건. `lq`는 소문자 원문, `tagName`은 정규화된 태그 이름(exact 비교용). */
	public record Criteria(UUID workspaceId, UUID userId, String query, Collection<String> statuses,
			Collection<String> types, UUID tagId, String language, String framework, Instant rankingAt) {

		public String lq() {
			return query.toLowerCase(Locale.ROOT);
		}

		public String tagName() {
			return query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
		}

		/** 필터를 모두 푼 조건(zero-result 안내용). 상태는 그대로 둔다. */
		public Criteria withoutFilters() {
			return new Criteria(workspaceId, userId, query, statuses, List.of(), null, null, null, rankingAt);
		}

		public boolean hasFilters() {
			return !types.isEmpty() || tagId != null || language != null || framework != null;
		}
	}

	public record Cursor(long scoreKey, Instant updatedAt, UUID id, Instant rankingAt) {
	}

	public record Row(UUID id, String type, String title, String status, Instant updatedAt, String language,
			String framework, boolean favorite, long scoreKey, boolean titleHit, boolean tagHit, boolean languageHit,
			boolean bodyHit, boolean codeHit, boolean errorHit) {
	}

	/** 발췌(본문·코드는 전체가 아니라 검색어 주변만 가져온다). `*Start`는 1부터, `*Total`은 원문 길이(문자). */
	public record Excerpts(String summary, String body, int bodyStart, int bodyTotal, String code, int codeStart,
			int codeTotal, String error, int errorStart, int errorTotal) {
	}

	public record RecentRow(UUID id, String type, String title, Instant updatedAt) {
	}

	private static final int BODY_INDEXED_CHARS = 100_000; // V8과 같은 값
	private static final int BODY_WINDOW = 220;
	private static final int CODE_WINDOW = 240;
	private static final int LEAD = 60;

	private final NamedParameterJdbcTemplate jdbc;

	public SearchQueryRepository(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<Row> search(Criteria c, Cursor cursor, int limit) {
		MapSqlParameterSource params = params(c);
		String cursorClause = "";
		if (cursor != null) {
			cursorClause = """
					WHERE (t.score_key < :cScore)
					   OR (t.score_key = :cScore AND t.updated_at < :cUpdated)
					   OR (t.score_key = :cScore AND t.updated_at = :cUpdated AND t.id > :cId)""";
			params.addValue("cScore", cursor.scoreKey())
					.addValue("cUpdated", OffsetDateTime.ofInstant(cursor.updatedAt(), java.time.ZoneOffset.UTC))
					.addValue("cId", cursor.id());
		}
		params.addValue("limit", limit);
		// score_key는 안쪽 SELECT의 별칭이라 cursor 조건은 한 겹 더 감싼 바깥에서 건다.
		String sql = "SELECT t.* FROM (" + scored(c, matchCondition()) + ") t " + cursorClause
				+ " ORDER BY t.score_key DESC, t.updated_at DESC, t.id ASC LIMIT :limit";
		return jdbc.query(sql, params, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("node_type"),
				rs.getString("title"), rs.getString("status"), instant(rs.getObject("updated_at", OffsetDateTime.class)),
				rs.getString("language"), rs.getString("framework"), rs.getBoolean("favorite"), rs.getLong("score_key"),
				rs.getBoolean("title_hit"), rs.getBoolean("tag_hit"), rs.getBoolean("lang_hit"),
				rs.getBoolean("body_hit"), rs.getBoolean("code_hit"), rs.getBoolean("error_hit")));
	}

	public int count(Criteria c) {
		String sql = "SELECT count(*) FROM (" + scored(c, matchCondition()) + ") t";
		Integer count = jdbc.queryForObject(sql, params(c), Integer.class);
		return count == null ? 0 : count;
	}

	/**
	 * 정확 조건으로 0건일 때의 "유사 결과". 제목 trigram 유사도가 완화된 임계값 이상인 항목을 유사도 순으로.
	 * 점수 정렬 대신 유사도 정렬이라 별도 쿼리다.
	 */
	public List<Row> similarByTitle(Criteria c, int limit) {
		MapSqlParameterSource params = params(c).addValue("limit", limit)
				.addValue("threshold", SearchRanking.RELAXED_SIMILARITY);
		String sql = "SELECT t.* FROM (" + scored(c, "similarity(lower(n.title), :lq) >= :threshold") + ") t"
				+ " ORDER BY similarity(lower(t.title), :lq) DESC, t.updated_at DESC, t.id ASC LIMIT :limit";
		return jdbc.query(sql, params, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("node_type"),
				rs.getString("title"), rs.getString("status"), instant(rs.getObject("updated_at", OffsetDateTime.class)),
				rs.getString("language"), rs.getString("framework"), rs.getBoolean("favorite"), rs.getLong("score_key"),
				true, false, false, false, false, false));
	}

	public List<RecentRow> recent(UUID workspaceId, int limit) {
		return jdbc.query("""
				SELECT id, node_type, title, updated_at FROM knowledge_nodes
				WHERE workspace_id = :ws AND status = 'ACTIVE'
				ORDER BY updated_at DESC, id ASC LIMIT :limit""",
				new MapSqlParameterSource("ws", workspaceId).addValue("limit", limit),
				(rs, i) -> new RecentRow(rs.getObject("id", UUID.class), rs.getString("node_type"),
						rs.getString("title"), instant(rs.getObject("updated_at", OffsetDateTime.class))));
	}

	/** 페이지에 오른 항목만 발췌를 가져온다(전체 결과의 본문·코드를 읽지 않기 위해 2단계로 나눴다). */
	public Map<UUID, Excerpts> excerpts(UUID workspaceId, Collection<UUID> ids, String firstTerm) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		// 본문 발췌 원천: Node 본문에 Solution/Error의 서술 필드를 이어 붙인다(subtype 서술도 "본문"으로 검색되므로).
		String body = "left(concat_ws(E'\\n', n.body_md, sr.approach_md, sr.steps_md, sr.verification_md, sr.tradeoffs_md, "
				+ "er.environment, er.reproduction_steps_md, er.cause_hypothesis_md), " + BODY_INDEXED_CHARS + ")";
		String sql = """
				SELECT n.id, n.summary,
				  %1$s AS body_pos, substr(%2$s, greatest(1, %1$s - %3$d), %4$d) AS body_win, left(%2$s, %4$d) AS body_head,
				  length(%2$s) AS body_total,
				  %5$s AS code_pos, substr(v.code, greatest(1, %5$s - %3$d), %6$d) AS code_win, left(v.code, %6$d) AS code_head,
				  length(v.code) AS code_total,
				  %7$s AS error_pos, substr(er.error_message, greatest(1, %7$s - %3$d), %6$d) AS error_win,
				  left(er.error_message, %6$d) AS error_head, length(er.error_message) AS error_total
				FROM knowledge_nodes n
				LEFT JOIN error_records er ON er.node_id = n.id
				LEFT JOIN solution_records sr ON sr.node_id = n.id
				LEFT JOIN snippets s ON s.node_id = n.id
				LEFT JOIN snippet_versions v ON v.snippet_node_id = s.node_id AND v.version_no = s.current_version_no
				WHERE n.workspace_id = :ws AND n.id IN (:ids)"""
				.formatted("strpos(lower(" + body + "), :term)", body, LEAD, BODY_WINDOW,
						"strpos(lower(v.code), :term)", CODE_WINDOW, "strpos(lower(er.error_message), :term)");
		Map<UUID, Excerpts> out = new HashMap<>();
		jdbc.query(sql, new MapSqlParameterSource("ws", workspaceId).addValue("ids", ids).addValue("term", firstTerm),
				rs -> {
					int bodyPos = rs.getInt("body_pos");
					int codePos = rs.getInt("code_pos");
					int errorPos = rs.getInt("error_pos");
					String bodyText = bodyPos > 0 ? rs.getString("body_win") : rs.getString("body_head");
					String codeText = codePos > 0 ? rs.getString("code_win") : rs.getString("code_head");
					String errorText = errorPos > 0 ? rs.getString("error_win") : rs.getString("error_head");
					out.put(rs.getObject("id", UUID.class), new Excerpts(rs.getString("summary"), bodyText,
							bodyPos > 0 ? Math.max(1, bodyPos - LEAD) : 1, rs.getInt("body_total"), codeText,
							codePos > 0 ? Math.max(1, codePos - LEAD) : 1, rs.getInt("code_total"), errorText,
							errorPos > 0 ? Math.max(1, errorPos - LEAD) : 1, rs.getInt("error_total")));
				});
		return out;
	}

	// ---- SQL assembly -----------------------------------------------------------------------

	/** 후보 선정 조건: 제목 부분 일치 · FTS · 태그 exact · 언어/framework · 코드 부분 일치 중 하나. */
	private static String matchCondition() {
		return "(h.title_contains OR h.fts_hit OR h.tag_hit OR h.lang_hit OR h.code_hit OR h.error_hit)";
	}

	private static String scored(Criteria c, String matchCondition) {
		StringBuilder filters = new StringBuilder();
		if (!c.types().isEmpty()) {
			filters.append(" AND n.node_type IN (:types)");
		}
		if (c.tagId() != null) {
			filters.append(" AND EXISTS (SELECT 1 FROM node_tags ft WHERE ft.node_id = n.id AND ft.tag_id = :tagId)");
		}
		if (c.language() != null) {
			filters.append(" AND s.language = :language");
		}
		if (c.framework() != null) {
			filters.append(" AND s.framework = :framework");
		}
		String score = String.format(Locale.ROOT, """
				CASE WHEN title_exact THEN %s WHEN title_prefix THEN %s WHEN title_contains THEN %s ELSE 0 END
				+ CASE WHEN tag_hit THEN %s ELSE 0 END
				+ %s * title_rank
				+ CASE WHEN lang_hit THEN %s ELSE 0 END
				+ CASE WHEN code_hit THEN %s ELSE 0 END
				+ CASE WHEN error_hit THEN %s ELSE 0 END
				+ %s * body_rank
				+ CASE WHEN favorite THEN %s ELSE 0 END
				+ %s * least(1, greatest(0, 1 - floor(extract(epoch from (cast(:rankingAt as timestamptz) - updated_at)) / 86400) / %d))""",
				SearchRanking.EXACT_TITLE, SearchRanking.TITLE_PREFIX, SearchRanking.TITLE_CONTAINS,
				SearchRanking.EXACT_TAG, SearchRanking.TITLE_FTS, SearchRanking.LANGUAGE_FRAMEWORK, SearchRanking.CODE,
				SearchRanking.ERROR_MESSAGE, SearchRanking.BODY_FTS, SearchRanking.FAVORITE, SearchRanking.RECENCY_MAX, SearchRanking.RECENCY_DAYS);
		return """
				WITH q AS (SELECT websearch_to_tsquery('simple', :q) AS tsq)
				SELECT t.*, round((%s)::numeric * 1000000)::bigint AS score_key FROM (
				  SELECT n.id, n.node_type, n.title, n.status, n.updated_at, s.language, s.framework,
				         (f.node_id IS NOT NULL) AS favorite,
				         h.title_exact, h.title_prefix, h.title_contains, h.tag_hit, h.lang_hit, h.code_hit, h.error_hit,
				         h.title_rank, h.body_rank,
				         (h.title_contains OR h.title_rank > 0) AS title_hit,
				         (h.body_rank > 0) AS body_hit
				  FROM knowledge_nodes n
				  CROSS JOIN q
				  LEFT JOIN snippets s ON s.node_id = n.id
				  LEFT JOIN snippet_versions v ON v.snippet_node_id = s.node_id AND v.version_no = s.current_version_no
				  LEFT JOIN error_records er ON er.node_id = n.id
				  LEFT JOIN solution_records sr ON sr.node_id = n.id
				  LEFT JOIN favorites f ON f.node_id = n.id AND f.user_id = :uid
				  CROSS JOIN LATERAL (SELECT
				      lower(n.title) = :lq AS title_exact,
				      lower(n.title) LIKE :lprefix ESCAPE '!' AS title_prefix,
				      lower(n.title) LIKE :lcontains ESCAPE '!' AS title_contains,
				      EXISTS (SELECT 1 FROM node_tags nt JOIN tags tg ON tg.id = nt.tag_id
				              WHERE nt.node_id = n.id AND tg.workspace_id = :ws AND tg.normalized_name = :tagName) AS tag_hit,
				      coalesce(s.language = :lq OR s.framework = :lq, false) AS lang_hit,
				      coalesce(v.code ILIKE :lcontains ESCAPE '!', false) AS code_hit,
				      coalesce(er.error_message ILIKE :lcontains ESCAPE '!', false) AS error_hit,
				      (n.search_vector @@ q.tsq OR coalesce(er.search_vector @@ q.tsq, false)
				          OR coalesce(sr.search_vector @@ q.tsq, false)) AS fts_hit,
				      -- ts_rank_cd는 비싸다(행당 수십 µs). 코드 일치처럼 FTS와 무관하게 후보가 된 행(예: 'public class'가 모든
				      -- Snippet에 있는 경우)은 어차피 0이므로 @@ 로 먼저 거른다. 결과는 같고 20k+ 후보에서 10배 이상 빠르다.
				      CASE WHEN n.search_vector @@ q.tsq THEN ts_rank_cd('{0,0,0,1}', n.search_vector, q.tsq, 34) ELSE 0 END AS title_rank,
				      greatest(CASE WHEN n.search_vector @@ q.tsq THEN ts_rank_cd('{0,1,0,0}', n.search_vector, q.tsq, 34) ELSE 0 END,
				               CASE WHEN er.search_vector @@ q.tsq THEN ts_rank_cd('{0,1,1,0}', er.search_vector, q.tsq, 34) ELSE 0 END,
				               CASE WHEN sr.search_vector @@ q.tsq THEN ts_rank_cd('{0,1,1,0}', sr.search_vector, q.tsq, 34) ELSE 0 END) AS body_rank) h
				  WHERE n.workspace_id = :ws AND n.status IN (:statuses)%s AND %s
				) t""".formatted(score, filters, matchCondition);
	}

	private static MapSqlParameterSource params(Criteria c) {
		String lq = c.lq();
		return new MapSqlParameterSource()
				.addValue("ws", c.workspaceId())
				.addValue("uid", c.userId())
				.addValue("q", c.query())
				.addValue("rankingAt", OffsetDateTime.ofInstant(c.rankingAt(), java.time.ZoneOffset.UTC))
				.addValue("lq", lq)
				.addValue("lprefix", LikeEscape.prefix(lq))
				.addValue("lcontains", LikeEscape.contains(lq))
				.addValue("tagName", c.tagName())
				.addValue("statuses", c.statuses())
				.addValue("types", c.types().isEmpty() ? List.of("") : c.types())
				.addValue("tagId", c.tagId())
				.addValue("language", c.language())
				.addValue("framework", c.framework());
	}

	private static Instant instant(OffsetDateTime time) {
		return time.toInstant();
	}
}
