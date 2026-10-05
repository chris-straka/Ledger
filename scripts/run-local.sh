#!/usr/bin/env bash
# Run the Ledger API without Docker, against a Postgres 18 cluster you can
# reach as a superuser. Same bootstrap as Compose: ledger_owner owns the
# database and runs Flyway; the app connects as the restricted ledger_app.
#
#   LEDGER_PG_ADMIN_URL=postgres://postgres@localhost:5432/postgres ./scripts/run-local.sh
#
# Env: LEDGER_DB (default ledger), LEDGER_API_PORT (default 18680),
# LEDGER_RESET=1 drops and recreates the database first. Passwords default to
# the dev-only values in .env.example. Runs in the foreground; Ctrl-C stops it.
set -euo pipefail

cd "$(dirname "$0")/.."

: "${LEDGER_PG_ADMIN_URL:?set LEDGER_PG_ADMIN_URL to a superuser postgres:// URL}"
DB="${LEDGER_DB:-ledger}"
PORT="${LEDGER_API_PORT:-18680}"
OWNER="${POSTGRES_OWNER:-ledger_owner}"
OWNER_PW="${POSTGRES_OWNER_PASSWORD:-ledger_owner_dev_only}"
APP="${POSTGRES_APP:-ledger_app}"
APP_PW="${POSTGRES_APP_PASSWORD:-ledger_app_dev_only}"

# host:port of the admin URL, reused for the JDBC URLs.
HOSTPORT="$(python3 -c 'import sys,urllib.parse as u; p=u.urlparse(sys.argv[1]); print(f"{p.hostname}:{p.port or 5432}")' "$LEDGER_PG_ADMIN_URL")"
JDBC="jdbc:postgresql://$HOSTPORT/$DB"

admin() { psql "$LEDGER_PG_ADMIN_URL" -v ON_ERROR_STOP=1 -qtA "$@"; }

if [ "${LEDGER_RESET:-0}" = 1 ]; then
  admin -c "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE);"
fi
admin <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$OWNER') THEN
    CREATE ROLE "$OWNER" LOGIN PASSWORD '$OWNER_PW';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$APP') THEN
    CREATE ROLE "$APP" LOGIN PASSWORD '$APP_PW';
  END IF;
END
\$\$;
SQL
if [ -z "$(admin -c "SELECT 1 FROM pg_database WHERE datname = '$DB';")" ]; then
  admin -c "CREATE DATABASE \"$DB\" OWNER \"$OWNER\";"
fi
admin -c "GRANT CONNECT ON DATABASE \"$DB\" TO \"$APP\";"

echo "[ledger] building boot jar"
./gradlew -q bootJar
JAR="$(ls build/libs/*.jar | grep -v plain | head -1)"
LIB="$(mktemp -d)"
(cd "$LIB" && jar xf "$OLDPWD/$JAR" BOOT-INF/lib)

echo "[ledger] migrating $JDBC as $OWNER"
java -cp "$LIB/BOOT-INF/lib/*" scripts/LocalMigrate.java "$JDBC" "$OWNER" "$OWNER_PW"
rm -rf "$LIB"

echo "[ledger] serving on http://127.0.0.1:$PORT as $APP"
exec java -jar "$JAR" \
  --server.address=127.0.0.1 --server.port="$PORT" \
  --spring.datasource.url="$JDBC" \
  --spring.datasource.username="$APP" \
  --spring.datasource.password="$APP_PW"
