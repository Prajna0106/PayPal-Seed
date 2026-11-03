#!/usr/bin/env bash
# Creates the first admin, who has nobody to add them, or resets a lost password. After that, admins manage staff on
# the desk's Staff page. For an existing email it only sets the new password (and links the member ID if you give one);
# roles and links are never changed here.
#   bash scripts/create-staff.sh prajna@example.com "Prajna Ojha" OFFICER,CHECKER,ADMIN
#   bash scripts/create-staff.sh prajna@example.com "Prajna Ojha" OFFICER,CHECKER,ADMIN <member-id>
# Needs the backend running. SEED_API_URL defaults to http://localhost:8080. SEED_STAFF_SERVICE_TOKEN comes from the
# environment or web/apps/desk/.env.local, and is the local default against a local backend. The password is asked
# for here, so it stays out of your shell history.
set -euo pipefail

if [ "$#" -lt 3 ]; then
  sed -n 2,9p "$0"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SEED_API_URL="${SEED_API_URL:-http://localhost:8080}"
if [ -z "${SEED_STAFF_SERVICE_TOKEN:-}" ] && [ -f "$ROOT/web/apps/desk/.env.local" ]; then
  SEED_STAFF_SERVICE_TOKEN="$(sed -n 's/^ *SEED_STAFF_SERVICE_TOKEN *= *//p' "$ROOT/web/apps/desk/.env.local" | tr -d '\r')"
fi
if [ -z "${SEED_STAFF_SERVICE_TOKEN:-}" ]; then
  case "$SEED_API_URL" in
    http://localhost:*|http://127.0.0.1:*) SEED_STAFF_SERVICE_TOKEN=local-dev-staff-service-token ;;
    *) echo "Set SEED_STAFF_SERVICE_TOKEN to the backend's staff service token." >&2; exit 1 ;;
  esac
fi

read -r -s -p "Password (12 or more characters): " PASSWORD
echo

EMAIL="$1" NAME="$2" ROLES="$3" MEMBER_ID="${4:-}" PASSWORD="$PASSWORD" \
  SEED_API_URL="$SEED_API_URL" SEED_STAFF_SERVICE_TOKEN="$SEED_STAFF_SERVICE_TOKEN" node --input-type=module -e '
const { EMAIL, NAME, ROLES, MEMBER_ID, PASSWORD, SEED_API_URL, SEED_STAFF_SERVICE_TOKEN } = process.env;
const response = await fetch(`${SEED_API_URL}/internal/auth/staff/accounts`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${SEED_STAFF_SERVICE_TOKEN}` },
  body: JSON.stringify({
    email: EMAIL,
    displayName: NAME,
    roles: ROLES.split(","),
    password: PASSWORD,
    ...(MEMBER_ID ? { memberId: MEMBER_ID } : {}),
  }),
});
if (!response.ok) {
  console.error(`Not saved: HTTP ${response.status} ${(await response.text()).slice(0, 300)}`);
  process.exit(1);
}
const saved = await response.json();
console.log(saved.created
  ? `Created. Staff id: ${saved.staffId}`
  : `Password reset for staff id ${saved.staffId}. Their roles are unchanged and their other sessions have ended.`);
'
