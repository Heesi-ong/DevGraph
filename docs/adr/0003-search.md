# ADR 0003: 검색 — PostgreSQL FTS + trigram, 태그는 조인으로 분리

## 상태
승인됨 (설계서 v1.4 §9.5, §15.4)

## 컨텍스트
제목/본문/코드/태그를 통합 검색하면서, 안정적인 페이지네이션과 태그 변경 시 인덱스 정합성을 함께
지켜야 한다.

## 결정
- `knowledge_nodes.search_vector`는 title/summary/body만 가중한 `tsvector`로 저장한다. 태그는 여기 넣지 않는다.
- 태그 일치는 `node_tags + tags` 조인으로 그때그때 계산한다(exact match 고정 가중치).
- Snippet 코드는 `pg_trgm` 유사도로 검색한다.
- 정렬은 `(score_key DESC, updatedAt DESC, id ASC)`로 고정하고, cursor 비교에는 원본 float `score`가 아니라
  `score_key = round(score * 1_000_000)::bigint`를 쓴다.

## 근거
- 태그를 tsvector에 포함하면 "태그 이름이 바뀔 때 벡터를 언제 다시 만드나"라는 미결 문제가 생긴다 —
  조인으로 분리해 이 문제 자체를 없앴다.
- float score를 cursor에 그대로 실어 `=` 비교하면 직렬화 정밀도 차이로 페이지 누락·중복이 생길 수 있어
  정수 키로 변환한다.
- 별도 검색 엔진(OpenSearch) 없이 개인 규모 요구를 충분히 처리할 수 있다(ADR 0001).

## 결과
- 장점: 태그 변경이 검색 인덱스 갱신을 요구하지 않는다. cursor pagination이 부동소수점 비교 버그 없이 안정적이다.
- 단점: 한국어 형태소 분석은 PostgreSQL 기본 설정만으로 제한적이다 — trigram으로 보완하고, 실패 사례가
  쌓이면 PGroonga/OpenSearch를 재검토한다(§15.4).
