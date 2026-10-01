# DevGraph

개발자 지식·코드·문제 해결 경험을 그래프로 연결해 검색·재사용하는 개인용 Developer Knowledge Base.

설계 기준 문서는 [DEVGRAPH_PRODUCT_DESIGN.md](DEVGRAPH_PRODUCT_DESIGN.md)(v2.0)이며, 각 챕터를 워드 문서로
나눈 버전은 [docs-word/](docs-word)에 있다. 구현 시 이 문서를 Source of Truth로 삼는다.

## 현재 상태

설계서 Phase 0~7 구현 완료.

| 영역 | 내용 |
|---|---|
| 인증·계정 | 가입/로그인, Refresh rotation + 재사용 감지(응답 유실 10초 유예), 세션 목록·폐기, 재인증, 비밀번호 변경, 계정 삭제(7일 유예) |
| 지식 | Concept/Note/Snippet(버전·복사 기록)/Error/Solution/Project/Resource, 태그·즐겨찾기, 보관·휴지통·영구 삭제 |
| 관계·그래프 | 타입 규칙이 있는 Relation, Backlink, focus/Workspace/Project Graph(상한·truncation 명시) |
| 검색 | PostgreSQL FTS + trigram, 가중치 랭킹, 안전한 highlight |
| 데이터 | 비동기 Export(ZIP + manifest 무결성, 일회성 다운로드) |
| 운영 | Docker Compose 배포, 백업/복원 스크립트와 복원 리허설, 구조화 로그, health, rate limit, 보안 헤더 |

측정과 점검 결과: [NFR 보고서](docs/NFR_REPORT.md) · [보안 점검표](docs/SECURITY_CHECKLIST.md) · [운영 Runbook](docs/RUNBOOK.md)
(Phase 1~5 안정성 수정 내역은 [docs/STABILITY_FIXES.md](docs/STABILITY_FIXES.md)). 알려진 제한과 이월 항목은 설계서 §19 Phase 7 결과에 있다.

## 로컬 실행

### Docker Compose로 전체 기동(개발용)

```bash
cp .env.example .env
docker compose -f docker-compose.dev.yml up --build
```

- Frontend: http://localhost:5173
- Backend health: http://localhost:8080/actuator/health

### 개별 실행(개발 중)

Backend (PostgreSQL은 `docker compose -f docker-compose.dev.yml up postgres`로 먼저 띄운다):

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

### 운영과 같은 구성으로 기동

```bash
cp .env.example .env     # DB_PASSWORD, JWT_SIGNING_KEY, PUBLIC_BASE_URL을 채운다
docker compose -f docker-compose.prod.yml up -d --build
```

배포·백업·복원·장애 대응은 [docs/RUNBOOK.md](docs/RUNBOOK.md). `JWT_SIGNING_KEY`가 없으면 backend는 기동을 거부한다
(개발 프로필만 로컬 전용 기본값을 쓴다).

## 테스트

```bash
cd backend && ./gradlew build      # JUnit + Testcontainers(PostgreSQL 16), Docker 필요
cd frontend && npm run test        # Vitest
cd frontend && npm run e2e         # Playwright (backend와 Vite를 함께 띄운다)
E2E_BASE_URL=http://localhost:8088 npm run e2e   # 이미 떠 있는 스택(예: prod compose)에 대해 실행
```

핵심 Workflow(설계서 §27.1의 14단계)는 `frontend/e2e/workflow.spec.ts`가 신규 계정으로 끝까지 따라간다.
부하 측정은 [ops/loadtest](ops/loadtest)(k6 + 시드 SQL)로 재현한다.

## 설계 결정 근거

핵심 아키텍처 결정은 [docs/adr](docs/adr)의 ADR을 참고한다.

- [0001-storage.md](docs/adr/0001-storage.md) — PostgreSQL 단일 저장소, 인접 목록 Graph
- [0002-auth.md](docs/adr/0002-auth.md) — Access JWT + Family 기반 Refresh Rotation
- [0003-search.md](docs/adr/0003-search.md) — PostgreSQL FTS + trigram, 태그는 조인으로 분리

도입하지 않은 기술(Redis, 별도 worker, object storage, 메시지 큐 등)과 이유는 설계서 §25에 있다.
