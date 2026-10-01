-- Phase 8: 한국어 본문 부분 일치. 'simple' FTS는 공백 단위 토큰이라 "트랜잭션을"로 쓴 본문이 "트랜잭션"으로 찾아지지 않는다.
-- 본문 앞 100,000자(FTS와 같은 범위)의 소문자 trigram 인덱스로 부분 문자열 일치를 후보에 포함한다.
-- 쓰기 비용(본문 수정 시 GIN 재색인)이 늘어난다 — 28k Node 부하 측정으로 검증했다(docs/NFR_REPORT.md §6).
CREATE INDEX ix_knowledge_nodes__body_trgm ON knowledge_nodes USING gin (lower(left(body_md, 100000)) gin_trgm_ops);
