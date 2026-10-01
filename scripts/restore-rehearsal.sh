#!/usr/bin/env bash
# 복원 리허설(비파괴): 덤프를 일회용 PostgreSQL에 복원하고, 원본과 테이블별 건수·내용 해시를 비교한 뒤,
# 같은 backend 이미지를 복원본에 붙여 실제 로그인까지 확인한다. 운영 DB는 건드리지 않는다.
# 사용: scripts/restore-rehearsal.sh backups/devgraph-XXXX.dump [로그인 이메일 비밀번호]
set -euo pipefail
cd "$(dirname "$0")/.."
DUMP="${1:?덤프 파일 경로가 필요합니다}"
EMAIL="${2:-}"; PASSWORD="${3:-}"
COMPOSE="${COMPOSE_FILE:-docker-compose.prod.yml}"
PROJECT="$(docker compose -f "$COMPOSE" config --format json | python3 -c 'import json,sys;print(json.load(sys.stdin)["name"])')"
NAME="devgraph-restore-check"
PGPASS="restore-$RANDOM$RANDOM"
cleanup() { docker rm -f "$NAME" "$NAME-app" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --name "$NAME" -e POSTGRES_DB=devgraph -e POSTGRES_USER=devgraph -e POSTGRES_PASSWORD="$PGPASS" postgres:16-alpine >/dev/null
for _ in $(seq 1 30); do docker exec "$NAME" pg_isready -U devgraph -d devgraph >/dev/null 2>&1 && break; sleep 1; done
START=$(date +%s)
case "$DUMP" in
  *.gpg) gpg --batch --quiet --pinentry-mode loopback --passphrase-file "${BACKUP_PASSPHRASE_FILE:?암호화된 덤프에는 BACKUP_PASSPHRASE_FILE이 필요합니다}" --decrypt "$DUMP" ;;
  *) cat "$DUMP" ;;
esac | docker exec -i "$NAME" pg_restore -U devgraph -d devgraph --no-owner --exit-on-error
echo "복원 소요: $(( $(date +%s) - START ))초"

# 테이블별 (행 수, 내용 해시)를 같은 SQL로 뽑아 비교한다. 내용 해시는 행 텍스트를 정렬해 md5한 값이다.
fingerprint() { # $1=실행 명령 접두사
  local run=("$@")
  "${run[@]}" psql -U devgraph -d devgraph -At -c "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' order by 1" |
  while read -r t; do
    # </dev/null: compose exec가 while-read의 표준 입력(남은 테이블 목록)을 삼키지 않게 한다.
    "${run[@]}" psql -U devgraph -d devgraph -At -c "select '$t', count(*), coalesce(md5(string_agg(x::text, '|' order by x::text)), '-') from public.\"$t\" x" </dev/null
  done
}
fingerprint docker compose -f "$COMPOSE" exec -T postgres > /tmp/devgraph-src.fp
fingerprint docker exec "$NAME" > /tmp/devgraph-restored.fp
if diff -q /tmp/devgraph-src.fp /tmp/devgraph-restored.fp >/dev/null; then
  echo "테이블 $(wc -l < /tmp/devgraph-src.fp | tr -d ' ')개 모두 건수·내용 해시 일치"
else
  echo "불일치:"; diff /tmp/devgraph-src.fp /tmp/devgraph-restored.fp; exit 1
fi
awk -F'|' '$2>0 {print "  " $1 ": " $2 "행"}' /tmp/devgraph-src.fp | head -30

if [ -n "$EMAIL" ]; then
  IMAGE="$(docker compose -f "$COMPOSE" images backend -q | head -1)"
  docker run -d --name "$NAME-app" --link "$NAME:postgres" -p 18080:8080 \
    -e SPRING_PROFILES_ACTIVE=prod -e DB_URL=jdbc:postgresql://postgres:5432/devgraph -e DB_USERNAME=devgraph -e DB_PASSWORD="$PGPASS" \
    -e JWT_SIGNING_KEY="rehearsal-$RANDOM$RANDOM$RANDOM-rehearsal" -e ALLOWED_ORIGINS=http://localhost:18080 \
    -e COOKIE_SECURE=false "$IMAGE" >/dev/null
  for _ in $(seq 1 60); do curl -fs localhost:18080/actuator/health/readiness >/dev/null 2>&1 && break; sleep 2; done
  CODE=$(curl -s -o /tmp/devgraph-login.json -w '%{http_code}' -X POST localhost:18080/api/v1/auth/login \
    -H 'content-type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
  [ "$CODE" = "200" ] && echo "복원본에서 로그인 성공(HTTP 200)" || { echo "로그인 실패(HTTP $CODE)"; exit 1; }
  TOKEN=$(python3 -c 'import json;print(json.load(open("/tmp/devgraph-login.json"))["accessToken"])')
  curl -fs -H "Authorization: Bearer $TOKEN" "localhost:18080/api/v1/nodes?size=1" | python3 -c 'import json,sys;d=json.load(sys.stdin);print("복원본에서 Node 조회 성공, 첫 페이지", len(d["items"]), "건")'
fi
rm -f /tmp/devgraph-src.fp /tmp/devgraph-restored.fp /tmp/devgraph-login.json
echo "리허설 통과"
