#!/usr/bin/env bash
# Crash/ambiguous-response harness. Kills a real JVM container
# with SIGKILL at two exact windows and proves transaction atomicity plus replay
# recovery. Uses its own Compose project (-p), own ports, and own volumes; never
# touches the developer's normal `ledger` project data. Exits nonzero on any
# false claim, timeout, empty database, or nonzero conservation sum.
#
# Proves: application-crash atomicity and ambiguous-response recovery.
# Does not prove: PostgreSQL durability under a database kill (separate test).
set -euo pipefail

PROJECT="ledger-crash"
API="http://127.0.0.1:8081"
TIMEOUT=90

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

cleanup() {
  docker compose -p "$PROJECT" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "CRASH-TEST FAIL: $1" >&2
  docker compose -p "$PROJECT" logs --tail=100 >&2 || true
  exit 1
}

wait_for() { # url deadline-seconds
  local start
  start=$(date +%s)
  until curl -sf "$1" >/dev/null 2>&1; do
    if (( $(date +%s) - start > $2 )); then
      fail "timeout waiting for $1"
    fi
    sleep 1
  done
}

# psql as the runtime role inside the crash project's postgres container.
db() { # sql
  docker compose -p "$PROJECT" exec -T \
    -e "PGPASSWORD=$POSTGRES_APP_PASSWORD" postgres \
    psql -U "$POSTGRES_APP" -d "$POSTGRES_DB" -tAc "$1" | tr -d ' '
}

api_post() { # path idempotency-key data
  curl -s -o /tmp/crash-resp.json -w "%{http_code}" -X POST "$API$1" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"
}

json_field() { # field
  python3 -c "import json; print(json.load(open('/tmp/crash-resp.json'))['$1'])"
}

start_stack() { # profiles (empty = ordinary fault-disabled app)
  if [ -n "${1:-}" ]; then
    LEDGER_PROFILES="$1" LEDGER_PG_PORT=5433 LEDGER_API_PORT=8081 \
      docker compose -p "$PROJECT" up -d --build postgres migrate ledger-api >/dev/null
  else
    LEDGER_PG_PORT=5433 LEDGER_API_PORT=8081 \
      docker compose -p "$PROJECT" up -d --build postgres migrate ledger-api >/dev/null
  fi
  wait_for "$API/actuator/health/readiness" "$TIMEOUT"
}

echo "== building the application jar under test"
./gradlew bootJar >/dev/null || fail "application build failed"

echo "== starting isolated project $PROJECT (crash profile armed)"
start_stack crash-test

echo "== creating accounts"
STATUS=$(curl -s -o /tmp/crash-resp.json -w "%{http_code}" -X POST "$API/v1/accounts" \
  -H 'Content-Type: application/json' \
  -d '{"code":"CASH","name":"Cash","currency":"CAD","type":"ASSET","overdraftPolicy":"DENY"}')
[ "$STATUS" = "201" ] || fail "account creation got $STATUS"
CASH=$(json_field accountId)
STATUS=$(curl -s -o /tmp/crash-resp.json -w "%{http_code}" -X POST "$API/v1/accounts" \
  -H 'Content-Type: application/json' \
  -d '{"code":"CAP","name":"Capital","currency":"CAD","type":"EQUITY","overdraftPolicy":"DENY"}')
[ "$STATUS" = "201" ] || fail "account creation got $STATUS"
CAPITAL=$(json_field accountId)

BODY1=$(printf '{"description":"crash-before-commit","effectiveAt":"2026-09-04T12:00:00Z","lines":[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"10000"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"10000"}]}' "$CASH" "$CAPITAL")
BODY2=$(printf '{"description":"crash-after-commit","effectiveAt":"2026-09-04T12:00:00Z","lines":[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"2500"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"2500"}]}' "$CASH" "$CAPITAL")

echo "== window 1: SIGKILL with the posting transaction open"
curl -sf -X POST "$API/internal/crash/arm?window=before-commit" >/dev/null \
  || fail "crash arm endpoint unreachable (is the crash-test profile active?)"
curl -s -X POST "$API/v1/postings" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: crash-key-1" -d "$BODY1" >/dev/null 2>&1 &
POSTER=$!
PAUSED=$(curl -sf "$API/internal/crash/paused?window=before-commit" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['paused'])")
[ "$PAUSED" = "True" ] || { kill "$POSTER" 2>/dev/null || true; fail "before-commit pause never engaged"; }
docker kill -s SIGKILL "$PROJECT-ledger-api-1" >/dev/null
wait "$POSTER" 2>/dev/null || true

echo "== restarting ordinary fault-disabled app"
start_stack ""

echo "== asserting nothing persisted and the key is reusable"
[ "$(db 'SELECT COUNT(*) FROM ledger_posting;')" = "0" ] \
  || fail "before-commit kill left posting rows behind"
STATUS=$(api_post /v1/postings crash-key-1 "$BODY1")
[ "$STATUS" = "201" ] || fail "replay after before-commit kill got $STATUS, want 201"

echo "== window 2: SIGKILL after commit, before the response"
docker compose -p "$PROJECT" stop ledger-api >/dev/null
start_stack crash-test
curl -sf -X POST "$API/internal/crash/arm?window=after-commit" >/dev/null
curl -s -X POST "$API/v1/postings" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: crash-key-2" -d "$BODY2" >/dev/null 2>&1 &
POSTER=$!
PAUSED=$(curl -sf "$API/internal/crash/paused?window=after-commit" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['paused'])")
[ "$PAUSED" = "True" ] || { kill "$POSTER" 2>/dev/null || true; fail "after-commit pause never engaged"; }
docker kill -s SIGKILL "$PROJECT-ledger-api-1" >/dev/null
wait "$POSTER" 2>/dev/null || true

echo "== restarting fault-disabled app and replaying the ambiguous request"
docker compose -p "$PROJECT" stop ledger-api >/dev/null
start_stack ""
STATUS=$(api_post /v1/postings crash-key-2 "$BODY2")
[ "$STATUS" = "200" ] || fail "replay after after-commit kill got $STATUS, want 200"
ID1=$(json_field postingId)
[ "$(db 'SELECT COUNT(*) FROM ledger_entry;')" = "4" ] \
  || fail "expected 4 entries (2+2)"
ID2=$(db "SELECT id FROM ledger_posting WHERE idempotency_key = 'crash-key-2';")
[ "$ID1" = "$ID2" ] || fail "replay returned $ID1 but the database holds $ID2"

echo "== final non-empty conservation audit"
[ "$(db 'SELECT COUNT(*) FROM ledger_posting;')" = "2" ] || fail "expected 2 postings"
[ "$(db 'SELECT COALESCE(SUM(signed_sum), 0) FROM v_conservation;')" = "0" ] \
  || fail "conservation sum is nonzero"

echo "CRASH-TEST PASS: kill-before-commit left nothing, kill-after-commit replayed exactly once"
