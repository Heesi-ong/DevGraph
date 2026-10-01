# Phase 1–5 안정성 수정 보고

기준 커밋: `555cdee` (Phase 5). 수정 브랜치: `fix/devgraph-stability`.
진행 중인 Phase 6 작업을 보존하기 위해 별도 Git worktree에서 수정·검증했다.

## 수정된 계약

- **Refresh / logout**: family를 먼저 비관적 잠금하고 refresh row를 다시 조회한다. 조건부 UPDATE로 토큰을 한 번만 소비하고 새 rotation row 발급까지 한 트랜잭션으로 처리한다. logout도 같은 family 잠금을 사용한다. 보안 감사는 잠금 트랜잭션이 끝난 후 별도 REQUIRES_NEW로 기록해 동시 요청의 연결 풀 고갈을 방지한다.
- **세션 목록**: family별 최신 rotation의 IP 정보를 반환하고, family 절대 만료 및 최신 refresh 만료·폐기·소비 상태를 검사한다. 응답에 `ipPrefix`로 반환한다(병합 후 설계서 §14.2 이름으로 통일).
- **Relation 방향**: 대칭/방향 관계 사이의 타입 변경에는 `sourceNodeId`, `targetNodeId`를 모두 전달해야 한다. 없으면 `409 RELATION_DIRECTION_REQUIRED`. 기존 두 노드 외의 endpoint는 거부한다. UI는 현재 화면에서 선택한 outgoing/incoming을 명시적으로 전송한다.
- **검색 cursor**: 최초 요청 시각 `rankingAt`을 cursor에 포함하고 후속 페이지에도 재사용한다. 하루 단위 최신성 점수도 날짜 경계에서 흔들리지 않는다. 기존 3필드 cursor는 새 계약과 호환되지 않아 검색을 처음부터 다시 요청해야 한다. URL 검색어 변경은 입력창에도 반영된다.
- **Workspace 정합성**: V11에서 노드·Snippet 버전·관계 작성자와 즐겨찾기·조회·활동 사용자의 `(workspace_id, user_id)`를 membership 복합 FK로 강제한다. 제약 이름을 명시하고, 기존 위반 데이터가 있으면 migration을 중단한다. 자동 삭제나 소유자 재배정은 하지 않는다.
- **Activity log**: 별도 writer의 REQUIRES_NEW 호출 전체를 listener에서 감싸 commit 단계 예외까지 잡는다. 부가 활동 기록의 실패가 이미 성공한 업무 응답을 실패로 바꾸지 않도록 했다.
- **Graph**: 이웃 노드를 DB에서 먼저 중복 제거한 뒤 제한한다. 같은 이웃에 다수 relation이 있어 오래된 다른 이웃이 누락되는 문제를 방지했다. 쿼리 행 제한에 도달하면 `QUERY_ROW_CAP`을 명시한다.
- **CI**: 실제 PostgreSQL과 backend/frontend를 구동하는 Playwright E2E job을 추가했다. 실패 trace/screenshot 및 보고서를 보관한다. 로컬 병행 실행용 포트·API proxy 설정도 지원한다.
- **추가 회귀 수정**: 전체 테스트에서 기존 시크릿 검사 연결 문자열 정규식의 반복 재탐색을 확인했다. 시작 경계와 possessive quantifier로 긴 단일 행의 검사 비용을 줄였으며 기존 탐지·성능 테스트를 통과했다.

## 유지되는 정책과 한계

- (병합 후 변경) 응답 유실은 설계서 §17.2의 10초 유예로 처리한다 — 직전 토큰만 유예 안에서 다시 받고 그 밖의 재사용은 family를 폐기한다. 아래 서술은 병합 전 브랜치 기준이다. 동시 refresh 테스트도 새 정책에 맞게 바꿨다.
- Refresh 재사용은 엄격하게 family 전체를 폐기한다. 동시 refresh 테스트의 성공 응답 1개 뒤에도 재사용 요청 때문에 family는 폐기된다. 응답 유실 후 이전 refresh를 재시도하면 재로그인이 필요할 수 있다. idempotency/grace 재발급 정책은 이번 수정에 포함하지 않았다.
- 보안 감사는 REQUIRES_NEW를 유지하지만 상태 변경 commit 뒤 기록한다. 두 commit 사이의 프로세스 종료까지 원자적으로 보장하지 않는다. 그 보장이 필요하면 transactional outbox 등 별도 설계가 필요하다.
- 검색 cursor는 시각 기반 점수만 고정한다. 페이지 사이의 노드 수정·즐겨찾기 변경까지 snapshot으로 고정하지 않는다.
- Membership 삭제는 관련 참조 데이터가 있으면 FK로 차단된다. 향후 공유 workspace 탈퇴·계정 purge 기능은 보존 정책에 따라 참조 처리 순서를 설계해야 한다.
- 대용량 검색 성능 측정, 한국어 본문 부분 검색, 검색 rate limit은 기존 후속 범위를 유지한다.

## Phase 6 통합 주의

1. 이 브랜치 수정은 원래 작업 디렉터리에 아직 병합하지 않았다. Phase 6 작업의 Relation/Search/Graph/SecretScanner 변경과 함께 검토해 통합한다. 수정 브랜치 파일로 전체 덮어쓰지 않는다.
2. **(병합 완료)** 이 브랜치의 migration은 Phase 6(V9)·Phase 7(V10 export_jobs)과 번호가 겹쳐 **V11**로 바꿔 병합했다.
3. Phase 6 검색의 새 필드 및 관계 타입 검증에도 `rankingAt`과 명시적 방향 endpoint 계약을 유지한다.
4. 병합 후 Phase 6 포함 전체 backend / frontend / E2E를 다시 실행한다. 이번 검증은 Phase 1–5 기준이며 실행 중인 Phase 6 코드의 검증을 대신하지 않는다.

## 검증

- Backend: `./gradlew test` 전체 87개 통과(실제 PostgreSQL Testcontainers).
- Frontend: lint, TypeScript/Vite build 통과. 기존 unit test 4개 통과.
- E2E: 격리 PostgreSQL과 전용 포트로 기존 16개 + 새 2개, 총 18개 통과.
- 신규 회귀 범위: 12개 동시 refresh, 최신 IP/만료 세션, 관계 방향 변환, 날짜 경계 cursor, membership FK 6종, 활동 로그 commit 실패, 다중 관계 graph 누락, 검색 URL/입력창 동기화.
- GitHub Actions 원격 실행과 Phase 6 병합 후 검증은 아직 수행하지 않았다. 대형 frontend chunk 경고는 기존 상태로 남아 있다.

## 병합 결과

`main`(Phase 7)에 병합. 충돌 5건 해결: RefreshService(브랜치의 family 잠금 구조 + 10초 유예), AuthController/SessionSummaryResponse(`ipPrefix` + 브랜치의 SessionQueryRepository), SecretScanner(main의 길이 상한 정규식 유지), playwright.config(외부 스택 `E2E_BASE_URL` 분기 + 브랜치의 서버 기동). 병합 후 백엔드 141개(Testcontainers PostgreSQL 16), Playwright 28개 통과.
