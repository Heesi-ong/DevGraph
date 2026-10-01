#!/usr/bin/env bash
# PostgreSQL 논리 백업(pg_dump custom format). 사용: scripts/backup.sh [출력 디렉터리]  (기본 ./backups)
# 보관: 일 1회 실행 + 7일 보존을 권장한다(docs/RUNBOOK.md). 덤프는 개인 데이터이므로 권한 600으로 만든다.
#
# 선택 설정(환경변수):
#   BACKUP_PASSPHRASE_FILE  암호 파일 경로. 설정하면 gpg 대칭 암호화(AES256)로 `*.dump.gpg`를 만든다.
#                           평문 덤프는 디스크에 쓰이지 않는다(파이프로 바로 암호화). 암호 파일은 덤프와 다른 곳에 보관할 것.
#   BACKUP_UPLOAD_CMD       백업 직후 실행할 오프사이트 전송 명령. 파일 경로가 마지막 인자로 붙는다.
#                           예: BACKUP_UPLOAD_CMD="rclone copyto --no-traverse" 는 지원하지 않으므로 래퍼 스크립트를 쓰는 것을 권장.
#                           암호화 없이 업로드하려 하면 거부한다.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-backups}"
COMPOSE="${COMPOSE_FILE:-docker-compose.prod.yml}"
PASSFILE="${BACKUP_PASSPHRASE_FILE:-}"
mkdir -p "$OUT"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
umask 077

if [ -n "${BACKUP_UPLOAD_CMD:-}" ] && [ -z "$PASSFILE" ]; then
  echo "오프사이트 전송(BACKUP_UPLOAD_CMD)에는 암호화(BACKUP_PASSPHRASE_FILE)가 필요합니다." >&2
  exit 1
fi

dump() { docker compose -f "$COMPOSE" exec -T postgres pg_dump -U devgraph -d devgraph -Fc --no-owner; }
decrypt_or_cat() { if [ -n "$PASSFILE" ]; then gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASSFILE" --decrypt "$1"; else cat "$1"; fi; }

if [ -n "$PASSFILE" ]; then
  [ -s "$PASSFILE" ] || { echo "암호 파일이 없거나 비어 있습니다: $PASSFILE" >&2; exit 1; }
  FILE="$OUT/devgraph-$STAMP.dump.gpg"
  dump | gpg --batch --yes --quiet --pinentry-mode loopback --passphrase-file "$PASSFILE" \
        --symmetric --cipher-algo AES256 --output "$FILE"
else
  FILE="$OUT/devgraph-$STAMP.dump"
  dump > "$FILE"
fi
# 깨진 덤프를 백업이라고 믿지 않도록(암호화된 경우 복호화까지 포함해) 목차를 읽어 본다.
decrypt_or_cat "$FILE" | docker compose -f "$COMPOSE" exec -T postgres pg_restore --list > /dev/null
find "$OUT" \( -name 'devgraph-*.dump' -o -name 'devgraph-*.dump.gpg' \) -mtime +7 -delete

if [ -n "${BACKUP_UPLOAD_CMD:-}" ]; then
  # shellcheck disable=SC2086
  $BACKUP_UPLOAD_CMD "$FILE"
fi
echo "$FILE"
