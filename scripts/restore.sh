#!/usr/bin/env bash
# Restore a scripts/backup.sh dump into a disposable database and audit it:
# row counts match the source, every posting closes, conservation is zero.
# The restored database is dropped on success and kept (named) on failure.
# Usage: ./scripts/restore.sh backups/ledger-<stamp>.dump
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

[ $# -eq 1 ] || { echo "usage: $0 <dump-file>" >&2; exit 2; }
DUMP="$1"
[ -f "$DUMP" ] || { echo "no such file: $DUMP" >&2; exit 2; }

RESTORE_DB="ledger_restore_$(date -u +%Y%m%dT%H%M%SZ | tr -d ':')"
echo "== restoring into disposable database $RESTORE_DB"

cleanup() {
  docker compose exec -T \
    -e "PGPASSWORD=$POSTGRES_OWNER_PASSWORD" postgres \
    psql -U "$POSTGRES_OWNER" -d postgres -c "DROP DATABASE IF EXISTS \"$RESTORE_DB\";" >/dev/null
}
trap cleanup EXIT

fail() {
  echo "RESTORE FAIL: $1 (database $RESTORE_DB kept for inspection)" >&2
  trap - EXIT
  exit 1
}

owner() { # sql
  docker compose exec -T \
    -e "PGPASSWORD=$POSTGRES_OWNER_PASSWORD" postgres \
    psql -U "$POSTGRES_OWNER" -d "$1" -tAc "$2" | tr -d ' '
}

owner postgres "CREATE DATABASE \"$RESTORE_DB\" OWNER \"$POSTGRES_OWNER\";" >/dev/null
BASENAME=$(basename "$DUMP")
docker compose cp "$DUMP" "postgres:/tmp/$BASENAME"
docker compose exec -T \
  -e "PGPASSWORD=$POSTGRES_OWNER_PASSWORD" postgres \
  pg_restore -U "$POSTGRES_OWNER" -d "$RESTORE_DB" "/tmp/$BASENAME" >/dev/null
docker compose exec -T postgres rm "/tmp/$BASENAME"

echo "== auditing restored database"
for table in ledger_account ledger_posting ledger_entry; do
  LIVE=$(owner "$POSTGRES_DB" "SELECT COUNT(*) FROM $table;")
  BACK=$(owner "$RESTORE_DB" "SELECT COUNT(*) FROM $table;")
  [ "$LIVE" = "$BACK" ] || fail "$table count diverged: live=$LIVE restored=$BACK"
  echo "   $table: $BACK rows match"
done
BAD=$(owner "$RESTORE_DB" \
  "SELECT COUNT(*) FROM v_posting_integrity WHERE declared_count <> actual_count OR signed_sum <> 0 OR distinct_accounts < 2;")
[ "$BAD" = "0" ] || fail "$BAD restored postings fail closure"
SUM=$(owner "$RESTORE_DB" "SELECT COALESCE(SUM(signed_sum), 0) FROM v_conservation;")
[ "$SUM" = "0" ] || fail "restored conservation sum is $SUM"

echo "RESTORE PASS: $RESTORE_DB matches live and audits clean"
