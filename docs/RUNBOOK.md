# DevGraph 운영 Runbook

대상: 단일 호스트 Docker Compose 배포(설계서 §21.1). TLS는 앞단(호스팅 edge/로드밸런서)이 끝낸다고 가정한다.

## 1. 배포

```bash
cp .env.example .env        # DB_PASSWORD, JWT_SIGNING_KEY(openssl rand -base64 48), PUBLIC_BASE_URL을 채운다
docker compose -f docker-compose.prod.yml up -d --build
curl -fs http://localhost:${HTTP_PORT:-80}/actuator/health/liveness   # {"status":"UP"}
```

- `postgres`와 `backend`는 호스트 포트를 열지 않는다. 외부에는 `frontend`(nginx)만 열린다.
- `JWT_SIGNING_KEY`가 없으면 backend가 기동을 거부한다. 키를 바꾸면 발급된 모든 Access Token이 무효가 된다(Refresh로 재발급되므로 사용자는 보통 알아채지 못한다).
- DB migration은 backend 기동 시 Flyway가 적용한다. `readiness`는 DB 연결과 적용되지 않은 migration이 없는지를 본다. 외부(nginx)에는 `liveness`만 노출한다.
- 업데이트: `git pull && docker compose -f docker-compose.prod.yml up -d --build`. migration은 앞으로만 간다(롤백 SQL 없음) — 배포 전에 §2 백업을 먼저 뜬다.

## 2. 백업

```bash
scripts/backup.sh backups        # pg_dump -Fc, 권한 600, 목차 검증, 7일 지난 덤프 삭제
```

- 일 1회 실행을 권장한다(cron 예: `15 3 * * * cd /srv/devgraph && scripts/backup.sh /srv/backups`). RPO 24h 목표가 이 주기에 의존한다.
- 덤프는 개인 데이터다. **암호화:** `BACKUP_PASSPHRASE_FILE=/path/to/pass.txt scripts/backup.sh`로 실행하면 `*.dump.gpg`(gpg 대칭 AES256)가 만들어지고 평문 덤프는 디스크에 쓰이지 않는다. 암호 파일(예: `openssl rand -base64 32 > pass.txt; chmod 600`)은 **덤프와 다른 곳에 보관**해야 하며, 잃어버리면 백업을 열 수 없다. 복원·리허설 스크립트는 `.gpg`를 같은 환경변수로 복호화한다.
- **오프사이트 전송:** `BACKUP_UPLOAD_CMD`(파일 경로가 마지막 인자로 붙는 명령, 예: 래퍼 스크립트로 `rclone copyto`)를 주면 백업 직후 실행한다. 암호화 없이는 거부한다. 전송 대상의 접근 제어와 보존은 운영자가 정한다.
- Export 임시 파일(`devgraph-export` 볼륨)은 백업 대상이 아니다. 만료 정리 배치가 지운다.

## 3. 복원

**먼저 리허설로 덤프가 쓸 수 있는지 확인한다(운영 DB를 건드리지 않는다).**

```bash
scripts/restore-rehearsal.sh backups/devgraph-XXXX.dump <로그인 이메일> <비밀번호>
```

일회용 PostgreSQL 16에 복원 → 모든 테이블의 행 수·내용 해시를 원본과 비교 → 같은 backend 이미지를 복원본에 붙여 실제 로그인과 Node 조회까지 확인한다. 끝나면 컨테이너를 정리한다.

**실제 복원(파괴적):**

```bash
scripts/backup.sh backups                                   # 현재 상태를 한 번 더 보관
scripts/restore.sh backups/devgraph-XXXX.dump --yes         # backend 중지 → pg_restore --clean → backend 시작
```

복원 뒤 `liveness` UP과 로그인을 확인한다. RTO 4h 목표 대비 측정 결과는 `docs/NFR_REPORT.md`에 있다.

## 3.1 지표

backend는 `MANAGEMENT_PORT`(compose 기본 8081)에서 `/actuator/health/*`, `/actuator/metrics`, `/actuator/prometheus`를 연다. 이 포트는 호스트에 공개하지 않고 nginx도 `liveness` 한 경로만 프록시한다. 지표를 보려면 같은 compose 네트워크에서 접근한다.

```bash
docker compose -f docker-compose.prod.yml exec frontend wget -qO- http://backend:8081/actuator/prometheus | grep ^devgraph_
```

사용량 지표는 횟수만 담는다: `devgraph_activity_total{action=…}`(Node/관계 상태 변경), `devgraph_snippet_copy_total`, `devgraph_search_zero_results_total`, `devgraph_graph_*`, `devgraph_export_*`. 제목·본문·검색어·사용자 식별자는 지표에 들어가지 않는다(테스트로 검증). 수집 파이프라인(Prometheus 서버 등)은 구성하지 않았다 — 필요하면 위 포트를 내부에서 scrape한다.

## 4. 점검과 장애 대응

| 증상 | 확인 | 조치 |
|---|---|---|
| 접속 불가 | `docker compose -f docker-compose.prod.yml ps`, `logs frontend backend` | 컨테이너 재시작은 `restart: unless-stopped`가 처리한다. 반복 재시작이면 `logs backend`에서 원인 확인(대개 DB 연결 또는 `JWT_SIGNING_KEY`) |
| 오류 응답에 `traceId` 제공됨 | `docker compose logs backend \| grep <traceId>` | 요청 로그는 JSON(ECS) 한 줄: `traceId`, `userIdHash`, `status`, `durationMs`, `errorCode`. 본문·쿠키·query는 기록되지 않는다 |
| 429가 잦음 | `Retry-After` 헤더, 로그의 `errorCode=RATE_LIMITED` | 한도는 `devgraph.rate-limit.*`(환경변수 `DEVGRAPH_RATE_LIMIT_*`). 로그인은 이메일+IP 대역별 실패 5회/분 |
| Export가 멈춤 | `export_jobs`에서 `PROCESSING`이 오래됨 | 5분 주기 정리 배치가 멈춘 job을 되돌리거나(최대 2회 재시도) FAILED로 확정한다. 크기 한도 초과는 `EXPORT_TOO_LARGE` |
| 디스크 증가 | `docker system df`, `devgraph-export` 볼륨 | 보존 배치가 activity 180일·audit 365일·휴지통 30일을 지운다(`devgraph.retention.*`) |

## 5. 계정 관련

- **관리자 비밀번호 초기화**(사용자가 잠겼을 때): 일회성 명령으로 임시 비밀번호를 만든다. 임시 비밀번호는 표준 출력에 **한 번만** 나온다(저장되지 않으며 로그에도 남지 않는다). 해당 사용자의 모든 세션이 끊기고, 다음 로그인에서 비밀번호 변경이 강제된다.

  ```bash
  docker compose -f docker-compose.prod.yml run --rm backend \
    --spring.main.web-application-type=none --devgraph.jobs.enabled=false \
    --admin.command=force-password-reset --admin.email=user@example.com
  ```


  사용자에게 임시 비밀번호를 전달하는 채널은 운영자가 정한다(이 시스템은 메일을 보내지 않는다).
- **계정 삭제**: 요청 7일 뒤 정리 배치(시간 단위)가 사용자 데이터를 삭제한다. `security_audit_logs`는 사용자 식별자를 끊은 채 보존 기간 동안 남는다.

## 6. 알려진 운영상 제한

- 단일 인스턴스 전제다. rate limit 카운터는 프로세스 메모리에 있어 재시작하면 초기화되고, backend를 여러 개로 늘리면 인스턴스별로 따로 센다(그때는 공유 저장소가 필요).
- 정상 경로의 로그아웃/세션 폐기는 즉시 반영되지만, 이미 발급된 Access Token(최대 15분)은 만료 전까지 유효하다(stateless JWT). 계정 삭제·제한은 새 토큰부터 반영된다.
- 외부 알림(이메일·Slack)은 없다. 장애 감지는 `liveness` 외부 모니터링(UptimeRobot 등)에 맡긴다.
