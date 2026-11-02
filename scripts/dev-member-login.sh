#!/usr/bin/env bash
# Runs Postgres, the backend and the member app on this machine for trying a real PayPal login.
# Reads SEED_INTERNAL_TOKEN from web/apps/member/.env.local, where the member app also finds its PayPal login values.
# Needs Java 21: on JAVA_HOME if it is set, otherwise on PATH.
# Log in at http://127.0.0.1:3000/login. Ctrl+C stops them; the database keeps its data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB=seed-local-auth-db
DB_PORT=55434

JAVA_VERSION="$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || true)"
case "$JAVA_VERSION" in
  *'version "21.'*) ;;
  *) echo "The backend needs Java 21. Set JAVA_HOME to a JDK 21 folder and run this again." >&2; exit 1 ;;
esac

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

export DATABASE_URL="jdbc:postgresql://localhost:$DB_PORT/paypalseed" SEED_ROLE=worker SPRING_PROFILES_ACTIVE=demo,dev

GRADLE=./gradlew
case "$OSTYPE" in msys*|cygwin*) GRADLE=./gradlew.bat ;; esac

export SEED_ACTOR_TOKEN_SECRETS="local:$(node -e 'console.log(require("crypto").randomBytes(32).toString("hex"))')"

trap 'kill 0' EXIT
(cd "$ROOT/backend" && "$GRADLE" :app:bootRun --console=plain) &
(cd "$ROOT/web" && npx --yes pnpm@10.22.0 --filter @paypal-seed/member dev) &
wait
