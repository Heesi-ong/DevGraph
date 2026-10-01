-- 설계서 §15.4 / §19 Phase 5: knowledge_nodes.search_vector (title=A, summary+body=C) + GIN/trigram 인덱스.
-- 태그는 vector에 넣지 않고 조회 시 node_tags 조인으로 계산한다(§15.4).
--
-- 'simple' 설정: 형태소 분석이 없어 한국어/영어/코드 식별자를 같은 규칙(소문자 + 공백·구두점 분리)으로 다룬다.
-- 부분 일치는 title trigram, 코드는 snippet_versions.code trigram(V6)이 맡는다.
--
-- tsvector는 값 하나가 1 MB를 넘으면 오류다. 본문은 최대 1 MB(§21.6)이므로 앞 100,000자만 색인한다.
-- 그보다 뒤의 내용은 전문 검색(FTS) 대상이 아니다(설계서에 한계로 기록).
ALTER TABLE knowledge_nodes ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('simple'::regconfig, title), 'A')
    || setweight(to_tsvector('simple'::regconfig, coalesce(summary, '') || ' ' || coalesce(left(body_md, 100000), '')), 'C')
) STORED;

CREATE INDEX ix_knowledge_nodes__search_vector ON knowledge_nodes USING gin (search_vector);
-- 제목의 부분 일치(LIKE '%q%')와 접두어 일치를 모두 가속한다. 조회는 lower(title)에 대해 쓴다.
CREATE INDEX ix_knowledge_nodes__title_trgm ON knowledge_nodes USING gin (lower(title) gin_trgm_ops);
