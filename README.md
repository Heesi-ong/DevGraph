# DevGraph

개발자 지식·코드·문제 해결 경험을 그래프로 연결해 검색·재사용하는 개인용 Developer Knowledge Base.

설계 기준 문서는 [DEVGRAPH_PRODUCT_DESIGN.md](DEVGRAPH_PRODUCT_DESIGN.md)(v1.4)이며, 각 챕터를 워드 문서로
나눈 버전은 [docs-word/](docs-word)에 있다. 구현 시 이 문서를 Source of Truth로 삼는다.

## 현재 상태

Phase 0(기획 및 프로젝트 기본 구조) — 빈 앱 스캐폴딩 단계. 실제 기능(인증, Knowledge, Snippet 등)은
아직 구현되지 않았다. 다음 Phase는 [설계서 §19 Phase 1](DEVGRAPH_PRODUCT_DESIGN.md)(Authentication).

## 로컬 실행

### Docker Compose로 전체 기동

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

## 테스트

```bash
cd backend && ./gradlew build      # JUnit + Testcontainers(PostgreSQL)
cd frontend && npm run test        # Vitest
cd frontend && npm run e2e         # Playwright
```

## 설계 결정 근거

핵심 아키텍처 결정은 [docs/adr](docs/adr)의 ADR을 참고한다.

- [0001-storage.md](docs/adr/0001-storage.md) — PostgreSQL 단일 저장소, 인접 목록 Graph
- [0002-auth.md](docs/adr/0002-auth.md) — Access JWT + Family 기반 Refresh Rotation
- [0003-search.md](docs/adr/0003-search.md) — PostgreSQL FTS + trigram, 태그는 조인으로 분리
