#!/usr/bin/env bash
# 운영 DB를 백업 덤프로 되돌린다(파괴적). 사용: scripts/restore.sh backups/devgraph-XXXX.dump --yes
# 현재 데이터는 덮어쓴다 — 먼저 scripts/backup.sh로 현재 상태를 한 번 더 떠 두는 것을 권장한다.
set -euo pipefail
cd "$(dirname "$0")/.."
DUMP="${1:?덤프 파일 경로가 필요합니다}"
[ "${2:-}" = "--yes" ] || { echo "현재 DB를 덮어씁니다. 확인하려면 두 번째 인자로 --yes를 주세요." >&2; exit 1; }
COMPOSE="${COMPOSE_FILE:-docker-compose.prod.yml}"
docker compose -f "$COMPOSE" stop backend
docker compose -f "$COMPOSE" exec -T postgres pg_restore -U devgraph -d devgraph --clean --if-exists --no-owner < "$DUMP"
docker compose -f "$COMPOSE" start backend
echo "복원 완료. /actuator/health/readiness 가 UP이 될 때까지 기다린 뒤 로그인으로 확인하세요."
