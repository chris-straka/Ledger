#!/usr/bin/env bash
# Reproducible interviewer walkthrough. Runs the full
# claim sequence against a fresh isolated project and asserts each result:
# accounts, opening capital, replay, conflict, expense, balances, conservation,
# raw bypass rejection, permission refusal, overdraft race, reversal, both
# crash windows, and a final audit. Exits nonzero on the first false claim.
set -euo pipefail

PROJECT="ledger-demo"
API="http://127.0.0.1:8082"
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
  echo "DEMO FAIL: $1" >&2
  exit 1
}

wait_for() {
  local start
  start=$(date +%s)
  until curl -sf "$1" >/dev/null 2>&1; do
    (( $(date +%s) - start > $2 )) && fail "timeout waiting for $1"
    sleep 1
  done
}

db() {
  docker compose -p "$PROJECT" exec -T \
    -e "PGPASSWORD=$POSTGRES_APP_PASSWORD" postgres \
    psql -U "$POSTGRES_APP" -d "$POSTGRES_DB" -tAc "$1" | tr -d ' '
}

step() {
  echo "== $1"
}

check() { # actual expected label
  [ "$1" = "$2" ] || fail "$3: got $1, want $2"
}

echo "== building the application jar under test"
./gradlew bootJar >/dev/null || fail "application build failed"

step "starting fresh isolated project $PROJECT"
LEDGER_PG_PORT=5434 LEDGER_API_PORT=8082 \
  docker compose -p "$PROJECT" up -d --build postgres migrate ledger-api >/dev/null
wait_for "$API/actuator/health/readiness" "$TIMEOUT"

mkaccount() { # code name type
  STATUS=$(curl -s -o /tmp/demo.json -w "%{http_code}" -X POST "$API/v1/accounts" \
    -H 'Content-Type: application/json' \
    -d "{\"code\":\"$1\",\"name\":\"$2\",\"currency\":\"CAD\",\"type\":\"$3\",\"overdraftPolicy\":\"DENY\"}")
  check "$STATUS" 201 "create account $1"
  python3 -c "import json; print(json.load(open('/tmp/demo.json'))['accountId'])"
}

mkposting() { # key description entries-json -> prints "status postingId"
  STATUS=$(curl -s -o /tmp/demo.json -w "%{http_code}" -X POST "$API/v1/postings" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $1" \
    -d "{\"description\":\"$2\",\"effectiveAt\":\"2026-09-04T12:00:00Z\",\"entries\":$3}")
  ID=$(python3 -c "import json; print(json.load(open('/tmp/demo.json')).get('postingId',''))")
  echo "$STATUS $ID"
}

balance() { # account-id
  curl -sf "$API/v1/accounts/$1/balance" | python3 -c "import json,sys; print(json.load(sys.stdin)['balanceMinor'])"
}

step "1. create Cash, Capital, Supplies"
CASH=$(mkaccount CASH Cash ASSET)
CAPITAL=$(mkaccount CAP Capital EQUITY)
SUPPLIES=$(mkaccount SUP Supplies EXPENSE)

step "2. opening capital 10,000 with type-aware balances"
ENTRIES=$(printf '[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"10000"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"10000"}]' "$CASH" "$CAPITAL")
read -r STATUS OPENING <<<"$(mkposting demo-open 'opening capital' "$ENTRIES")"
check "$STATUS" 201 "opening capital"
check "$(balance "$CASH")" 10000 "cash after opening"
check "$(balance "$CAPITAL")" 10000 "capital after opening"

step "3. replay proves unchanged posting/entry counts"
BEFORE_P=$(db 'SELECT COUNT(*) FROM ledger_posting;')
BEFORE_E=$(db 'SELECT COUNT(*) FROM ledger_entry;')
read -r STATUS REPLAY <<<"$(mkposting demo-open 'opening capital' "$ENTRIES")"
check "$STATUS" 200 "idempotent replay"
[ "$REPLAY" = "$OPENING" ] || fail "replay returned a different posting id"
check "$(db 'SELECT COUNT(*) FROM ledger_posting;')" "$BEFORE_P" "posting count after replay"
check "$(db 'SELECT COUNT(*) FROM ledger_entry;')" "$BEFORE_E" "entry count after replay"

step "4. same key, different body is a 409"
OTHER=$(printf '[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"9000"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"9000"}]' "$CASH" "$CAPITAL")
read -r STATUS _ <<<"$(mkposting demo-open 'opening capital' "$OTHER")"
check "$STATUS" 409 "conflicting reuse"

step "5. expense 2,500 with balances plus conservation"
SPEND_ENTRIES=$(printf '[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"2500"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"2500"}]' "$SUPPLIES" "$CASH")
read -r STATUS SPEND <<<"$(mkposting demo-spend 'buy supplies' "$SPEND_ENTRIES")"
check "$STATUS" 201 "expense posting"
check "$(balance "$CASH")" 7500 "cash after spend"
check "$(balance "$SUPPLIES")" 2500 "supplies after spend"
check "$(db 'SELECT COALESCE(SUM(signed_sum),0) FROM v_conservation;')" 0 "conservation"

step "6. unbalanced raw insert fails at commit"
# A 32-byte fingerprint passes every row CHECK, so the statement succeeds and
# only the commit-time count trigger rejects the entry-less header.
db "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind, currency_code, entry_count, description, effective_at) VALUES ('demo-raw', decode(repeat('ab', 32), 'hex'), 'STANDARD', 'CAD', 2, 'raw bypass', now());" \
  >/dev/null 2>&1 && fail "raw header unexpectedly committed" || true
check "$(db 'SELECT COUNT(*) FROM ledger_posting WHERE idempotency_key = '"'"'demo-raw'"'"';')" 0 "raw header absent"

step "7. update/delete as ledger_app is refused"
db "UPDATE ledger_entry SET amount_minor_units = 1;" >/dev/null 2>&1 \
  && fail "runtime UPDATE unexpectedly succeeded" || true
[ "$(db 'SELECT COUNT(*) FROM ledger_entry;')" = "4" ] || fail "entry count changed"

step "8. coordinated overdraft race: one winner, nonnegative final"
# Cash holds 7,500 after the expense: two 6,000 withdrawals elect one winner.
RACE_A=$(printf '[{"accountId":"%s","side":"DEBIT","amountMinorUnits":"6000"},{"accountId":"%s","side":"CREDIT","amountMinorUnits":"6000"}]' "$CAPITAL" "$CASH")
RACE_OUT=$(mktemp)
mkposting demo-race-a 'race withdrawal a' "$RACE_A" >"$RACE_OUT" &
mkposting demo-race-b 'race withdrawal b' "$RACE_A" >"$RACE_OUT.b" &
wait
STATUS_A=$(cut -d' ' -f1 "$RACE_OUT")
STATUS_B=$(cut -d' ' -f1 "$RACE_OUT.b")
SORTED=$(printf '%s %s' "$STATUS_A" "$STATUS_B" | tr ' ' '\n' | sort -n | tr '\n' ' ')
[ "$SORTED" = "201 409 " ] || fail "overdraft race gave $STATUS_A and $STATUS_B, want one 201 and one 409"
check "$(balance "$CASH")" 1500 "cash after race"

step "9. reverse the expense; the original still exists"
STATUS=$(curl -s -o /tmp/demo.json -w "%{http_code}" -X POST "$API/v1/postings/$SPEND/reversals" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: demo-rev-1" \
  -d '{"reason":"wrong order","effectiveAt":"2026-09-04T12:00:00Z"}')
check "$STATUS" 201 "reversal"
KIND=$(curl -sf "$API/v1/postings/$SPEND" | python3 -c "import json,sys; print(json.load(sys.stdin)['kind'])")
check "$KIND" STANDARD "original kind after reversal"
check "$(balance "$CASH")" 4000 "cash after reversal"
check "$(balance "$SUPPLIES")" 0 "supplies after reversal"

step "10. both crash windows"
./scripts/crash-recovery.sh >/tmp/demo-crash.log 2>&1 || { tail -20 /tmp/demo-crash.log; fail "crash harness failed"; }

step "11. final non-empty audit of closure and conservation"
./scripts/verify.sh >/dev/null || fail "verify failed against the dev stack"

echo "DEMO PASS: every claim above was asserted, not printed"
