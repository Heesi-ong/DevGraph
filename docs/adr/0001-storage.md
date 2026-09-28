# ADR 0001: 저장소 — PostgreSQL 단일 저장소, 인접 목록 Graph

## 상태
승인됨 (설계서 v1.4 §0, §12, §13)

## 컨텍스트
DevGraph는 관계형 데이터(사용자·태그·버전), 전문 검색, Graph 탐색을 함께 다뤄야 한다. 후보는
(a) PostgreSQL 단일 저장소, (b) PostgreSQL + 별도 검색 엔진(OpenSearch), (c) PostgreSQL + Graph DB(Neo4j)였다.

## 결정
- 단일 PostgreSQL 인스턴스를 Source of Truth로 쓴다.
- Graph는 `knowledge_nodes` + `knowledge_relations` 인접 목록으로 저장하고 recursive CTE로 1~3 depth를 탐색한다.
- 검색은 PostgreSQL FTS(`tsvector`) + `pg_trgm`으로 처리한다.
- Redis, Graph DB, Elasticsearch/OpenSearch는 MVP에서 제외한다.

## 근거
- 개인/소규모 사용 규모에서는 별도 저장소 운영 비용이 이점보다 크다(§25 기술적 과설계 검토).
- 트랜잭션 일관성(Node·Relation·태그를 한 트랜잭션으로 묶는 생성 흐름)이 단일 저장소에서 단순하다.
- 전환 기준(§13.4)을 명시해 실제 병목이 확인되면 재검토한다 — 지금 미리 도입하지 않는다.

## 결과
- 장점: 운영 구성 단순(Docker Compose 3개 서비스), 백업/복구 대상이 하나.
- 단점: 4 depth 이상 graph 순회나 대규모 edge에서는 성능 한계가 있다(§13.4 전환 기준으로 관리).
