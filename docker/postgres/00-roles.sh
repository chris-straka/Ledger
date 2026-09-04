#!/bin/sh
# Fresh-cluster role bootstrap only. Runs once, at first database initialization,
# inside the postgres entrypoint. Schema, tables, and table grants are Flyway-owned
# (Phase 2 migrations) — this script creates only the runtime role so the app can
# connect with least privilege.
set -eu

# The entrypoint does not guarantee these are exported into init scripts;
# fall back to its own defaults rather than dying under `set -u`.
: "${POSTGRES_USER:=postgres}"
: "${POSTGRES_DB:=$POSTGRES_USER}"
: "${LEDGER_APP:?LEDGER_APP is required}"
: "${LEDGER_APP_PASSWORD:?LEDGER_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${LEDGER_APP}') THEN
    CREATE ROLE "${LEDGER_APP}" LOGIN PASSWORD '${LEDGER_APP_PASSWORD}';
  END IF;
END
\$\$;
GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO "${LEDGER_APP}";
EOSQL
