#!/usr/bin/env bash
# PostgreSQL 논리 백업(pg_dump custom format). 사용: scripts/backup.sh [출력 디렉터리]  (기본 ./backups)
# 보관: 일 1회 실행 + 7일 보존을 권장한다(docs/RUNBOOK.md). 덤프는 개인 데이터이므로 권한 600으로 만든다.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-backups}"
COMPOSE="${COMPOSE_FILE:-docker-compose.prod.yml}"
mkdir -p "$OUT"
FILE="$OUT/devgraph-$(date -u +%Y%m%dT%H%M%SZ).dump"
umask 077
docker compose -f "$COMPOSE" exec -T postgres pg_dump -U devgraph -d devgraph -Fc --no-owner > "$FILE"
# 깨진 덤프를 백업이라고 믿지 않도록 목차를 읽어 본다.
docker compose -f "$COMPOSE" exec -T postgres pg_restore --list < "$FILE" > /dev/null
find "$OUT" -name 'devgraph-*.dump' -mtime +7 -delete
echo "$FILE"
