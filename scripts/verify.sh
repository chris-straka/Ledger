#!/usr/bin/env bash
# Non-empty integrity audit. Checks the ledger reachable
# at 127.0.0.1:5432 as the runtime role: posting closure on every posting,
# per-currency conservation at zero, and proof the database is not empty.
# Exits nonzero on any violation — pretty output is secondary to assertions.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

PSQL=(docker compose exec -T -e "PGPASSWORD=$POSTGRES_APP_PASSWORD" postgres
  psql -U "$POSTGRES_APP" -d "$POSTGRES_DB" -tAc)

fail() {
  echo "VERIFY FAIL: $1" >&2
  exit 1
}

POSTINGS=$("${PSQL[@]}" "SELECT COUNT(*) FROM ledger_posting;")
[ "$POSTINGS" -ge 1 ] || fail "empty database: no postings to audit"

BAD_CLOSURE=$("${PSQL[@]}" \
  "SELECT COUNT(*) FROM v_posting_integrity WHERE declared_count <> actual_count OR signed_sum <> 0 OR distinct_accounts < 2;")
[ "$BAD_CLOSURE" = "0" ] || fail "$BAD_CLOSURE postings fail closure (count, sum, or accounts)"

NONZERO=$("${PSQL[@]}" \
  "SELECT COUNT(*) FROM v_conservation WHERE signed_sum <> 0;")
[ "$NONZERO" = "0" ] || fail "conservation violated in $NONZERO currencies"
DETAIL=$("${PSQL[@]}" "SELECT currency_code || '=' || signed_sum FROM v_conservation;")

echo "VERIFY PASS: $POSTINGS postings closed and balanced; conservation per currency: $DETAIL"
