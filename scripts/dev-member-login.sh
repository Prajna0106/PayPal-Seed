#!/usr/bin/env bash
# Runs Postgres, the backend and the member app on this machine for trying a real PayPal login.
# Reads SEED_INTERNAL_TOKEN from web/apps/member/.env.local, where the member app also finds its PayPal login values.
# Log in at http://127.0.0.1:3000/login. Ctrl+C stops them; the database keeps its data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB=seed-local-auth-db
DB_PORT=55434   # not 5432, which a Postgres installed on the machine may already be using

# Read the values as plain text, so nothing in the file can run as a command or end up in an error message.
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  case "$line" in
    SEED_INTERNAL_TOKEN*)
      key="${line%%=*}"; value="${line#*=}"
      key="${key//[[:space:]]/}"; value="${value#"${value%%[![:space:]]*}"}"
      export "$key=$value"
      ;;
  esac
done < "$ROOT/web/apps/member/.env.local"

docker start "$DB" >/dev/null 2>&1 || docker run -d --name "$DB" -e POSTGRES_USER=seed -e POSTGRES_PASSWORD=seed_password \
  -e POSTGRES_DB=paypalseed -v "$DB-data:/var/lib/postgresql/data" -p "$DB_PORT:5432" postgres:16-alpine >/dev/null
until docker exec "$DB" pg_isready -U seed >/dev/null 2>&1; do sleep 1; done

# demo,dev keeps the Ana demo on /login working alongside the real login. Never use these profiles when deployed.
export DATABASE_URL="jdbc:postgresql://localhost:$DB_PORT/paypalseed" SEED_ROLE=worker SPRING_PROFILES_ACTIVE=demo,dev
JDK21="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
[ -d "$JDK21" ] && export JAVA_HOME="$JDK21"

GRADLE=./gradlew
case "$OSTYPE" in msys*|cygwin*) GRADLE=./gradlew.bat ;; esac

# Both sides need the same key. A new one each run is fine: it only proves requests came from these web apps.
export SEED_ACTOR_TOKEN_SECRETS="local:$(node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))')"

trap 'kill 0' EXIT
(cd "$ROOT/backend" && "$GRADLE" :app:bootRun --console=plain) &
(cd "$ROOT/web" && npx --yes pnpm@10.22.0 --filter @paypal-seed/member dev) &
wait
