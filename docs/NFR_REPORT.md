# DevGraph NFR 측정 보고서 (Phase 7)

측정일: 2026-10-01. 설계서 §22, §22.1의 측정 조건을 함께 기록한다. 재현: `ops/loadtest/` (seed.sql, k6.js, compose.loadtest.yml).

## 1. 측정 조건 (§22.1)

| 항목 | 값 |
|---|---|
| 호스트 | Apple Silicon Mac(aarch64), Docker Desktop VM 10 vCPU / 7.7 GiB. **load generator(k6)·nginx·backend·PostgreSQL이 같은 VM/호스트에서 돈다**(별도 인스턴스 아님) |
| 스택 | `docker-compose.prod.yml`(nginx → Spring Boot `prod` 프로파일 → PostgreSQL 16.15 Alpine). 요청은 nginx(:8088)를 통과 |
| PostgreSQL 설정 | 기본값(`shared_buffers=128MB`, `work_mem=4MB`, `max_connections=100`). 변경 없음. 인덱스는 migration V1~V10 그대로 |
| Dataset | 한 Workspace에 **Node 28,000개**(CONCEPT 8,000 / NOTE 12,000 / SNIPPET 8,000, 현재 버전 1개씩, 2% ARCHIVED), **Relation 109,463개**(RELATED_TO), 평균 degree **8.0**, 최대 213, 상위 1% 경계 197. DB 크기 103 MB. ERROR/SOLUTION/PROJECT/RESOURCE Node와 Tag는 시드하지 않았다(해당 타입의 부하는 이 측정에 없다). 본문은 약 120단어 한/영 혼합 무작위 문장 |
| 캐시 | **cold**: backend 재시작 직후 첫 15초(PostgreSQL 캐시는 재시작하지 않았으므로 backend/JIT/커넥션 풀만 cold). **warm**: 이어서 60초 |
| 도구·시나리오 | k6 v2.1.0, **20 VU 폐쇄 루프**, 사용자 1명 계정 공유. 요청 비율: 목록 30%(전체/CONCEPT/NOTE, size 20), 상세 30%(무작위 Node), 검색 25%(한/영 단어, 두 단어, 코드 부분 문자열 `public class` 등), focus graph 15%(depth 2, maxNodes 200; 절반은 degree 213 hub 중심). 사용자별 rate limit만 풀어 두었다(한 계정으로 20 user를 흉내 내기 때문) |

## 2. 결과 (p95, 오류율 0%)

| 지표 | 목표 | cold(15s, 2,102 req) | warm(60s, 8,376 req) | 판정 |
|---|---|---|---|---|
| 목록 | ≤ 500 ms | 170 ms | 174 ms | ✅ |
| 상세 | ≤ 500 ms | 169 ms | 178 ms | ✅ |
| 검색 | ≤ 800 ms | 510 ms | 509 ms | ✅ |
| focus graph(200 node) | ≤ 1 s | 204 ms | 197 ms | ✅ |
| 처리량 | — | 132 req/s | 139 req/s | — |

p99(warm): 목록 235 ms, 상세 244 ms, 검색 598 ms, graph 246 ms. 최대(warm): 검색 882 ms.

### 측정이 찾아낸 결함과 수정 (측정 전 → 후)

최초 측정에서 **검색이 목표 미달**이었다(warm p95 **3.66 s**, cold p95 1.11 s; 나머지는 통과). 원인은 `ts_rank_cd`를 FTS와 무관하게 후보가 된 모든 행에 계산한 것이다. 코드 일치로 28k 중 7,840개 Snippet이 후보가 되는 `public class` 같은 질의가 단건 1.57 s였다(`EXPLAIN ANALYZE`로 순위 계산이 1.4 s임을 확인). 순위 함수 앞에 `@@`로 FTS 일치 여부를 먼저 거르도록 바꿔(`SearchQueryRepository`) 같은 질의가 0.14 s가 되었다. 순위 결과는 동일하다(검색 통합 테스트 통과). 위 표는 수정 후 수치다.

## 3. 다른 NFR

| 항목 | 결과 |
|---|---|
| NFR-03 Security | 보안 점검표(`docs/SECURITY_CHECKLIST.md`) 참조. Java 의존성·OS 패키지 CRITICAL/HIGH 0(Trivy), 프론트 prod 의존성 critical/high 0. 기반 이미지 내 `pebble` Go 바이너리 HIGH 14건 잔존(사용하지 않음). Tenant isolation은 도메인별 통합 테스트, 인증 누락은 `ApiAuthSweepIntegrationTest`가 자동 검증 |
| NFR-02 Frontend | localhost·네트워크 제한 없는 Chromium에서 LCP 64–108 ms(`/`, `/library`, `/snippets`, `/problems`, `/settings`, `/snippets/new`). **실제 네트워크/저사양 기기 조건의 측정은 아니다**(Lighthouse 미실행). Monaco는 에디터 화면 진입 전에는 요청되지 않음(`/snippets/new`에서만 1회 요청)을 확인. 초기 JS 5개 파일 |
| NFR-04 Scalability | 28k Node/109k Relation에서 위 결과. **50 concurrent user, 20k+ Node/Workspace의 상한 탐색은 하지 않았다**(20 VU만). 큰 Workspace에서 `GET /search`는 후보 전체를 점수화한 뒤 상위를 취하므로 Node 수에 선형이다 — 100k대로 가면 별도 측정과 인덱스/질의 재설계가 필요하다 |
| NFR-05 Reliability | `restart: unless-stopped` + 컨테이너 HEALTHCHECK, daily backup 스크립트. **복원 리허설 실측**: 103 MB DB(덤프 9.8 MB) → 일회용 PostgreSQL 16에 복원 3초(전체 리허설 17초: backend 기동·로그인 포함), 23개 테이블 전부 행 수와 내용 해시 일치, 복원본으로 backend를 띄워 실제 로그인·조회 성공. RTO 4h/RPO 24h 목표에 대해 이 규모에서는 크게 여유가 있으나, 호스트 전체 장애(디스크 소실)에서 백업을 다른 호스트로 가져오는 시간은 측정하지 않았다 |
| NFR-07 Accessibility | axe(WCAG 2a/2aa)로 Dashboard·Library·Settings 360px에서 critical/serious 위반 0. **스크린 리더 수동 점검은 하지 않았다** |
| NFR-08 Responsive | Playwright 360px에서 Dashboard·Library·Settings 가로 스크롤 없음. 다른 route(Snippet 상세, Graph 등)의 viewport 행렬은 자동화하지 않았다. Graph 편집은 desktop-first |
| 테스트 | Backend 133개 통합/단위 테스트: embedded PostgreSQL 14와 **Testcontainers PostgreSQL 16 양쪽 전부 통과**. Playwright 26개(개발 서버와 prod 스택 nginx 양쪽 통과) |

## 4. 한계와 미측정

- load generator와 서버가 같은 호스트라 서로 CPU를 나눈다. 실제 배포(별도 호스트, 네트워크 지연)의 수치와 다르다.
- 20 VU, 1개 계정, 60초. 장시간(soak) 측정, 메모리 증가 추세, 커넥션 풀 포화 시점은 측정하지 않았다.
- Export 대용량(수십 MB) 처리 시간과 메모리, 동시 Export는 측정하지 않았다(크기 상한 250 MB와 단위/통합 테스트로만 검증).
- 쓰기 부하(생성·수정·관계 추가)는 측정하지 않았다. 읽기 부하만 NFR-01 대상이다.

## 5. 재측정 (stability 브랜치 병합 후, 2026-10-01)

병합 커밋 `19099c5` 기준으로 prod 스택을 새로(빈 DB, V1~V11 적용) 올려 같은 dataset(28k Node, 같은 seed)과 같은 조건으로 다시 측정했다. Playwright 28개 전부 통과.

| 지표 (p95) | cold(15s) | warm(60s) | 목표 |
|---|---|---|---|
| 목록 | 178 ms | 156 ms | ≤ 500 ms |
| 상세 | 177 ms | 162 ms | ≤ 500 ms |
| 검색 | 519 ms | 465 ms | ≤ 800 ms |
| focus graph | 187 ms | 179 ms | ≤ 1 s |

오류율 0%, warm 처리량 153 req/s. §2 수치와 같은 수준이며 목표를 모두 만족한다(그래프 이웃 중복 제거·세션 쿼리 변경에 따른 회귀 없음).
