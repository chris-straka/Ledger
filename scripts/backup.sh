#!/usr/bin/env bash
# Physical backup of the dev ledger database (custom-format dump). The dump is
# taken as ledger_owner so grants, triggers, and views restore intact; the
# matching restore + audit lives in scripts/restore.sh. Dumps land in
# backups/ (gitignored) and never in the repo.
set -euo pipefail

cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
source .env
set +a

mkdir -p backups
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT="backups/ledger-$STAMP.dump"

docker compose exec -T \
  -e "PGPASSWORD=$POSTGRES_OWNER_PASSWORD" postgres \
  pg_dump -U "$POSTGRES_OWNER" -d "$POSTGRES_DB" -Fc -f "/tmp/ledger-$STAMP.dump"
docker compose cp "postgres:/tmp/ledger-$STAMP.dump" "$OUT"
docker compose exec -T postgres rm "/tmp/ledger-$STAMP.dump"
ls -la "$OUT"
echo "BACKUP OK: $OUT"
