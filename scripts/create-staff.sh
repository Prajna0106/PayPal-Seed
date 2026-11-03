#!/usr/bin/env bash
# Creates the first admin, who has nobody to add them. After that, admins add staff on the desk's Staff page.
# Also updates the account with this email, for example to reset a lost password.
#   bash scripts/create-staff.sh prajna@example.com "Prajna Ojha" OFFICER,CHECKER,ADMIN
#   bash scripts/create-staff.sh prajna@example.com "Prajna Ojha" OFFICER,CHECKER,ADMIN <member-id>
# Needs the backend running. SEED_API_URL defaults to http://localhost:8080. SEED_INTERNAL_TOKEN comes from the
# environment or web/apps/member/.env.local. The password is asked for here, so it stays out of your shell history.
set -euo pipefail

if [ "$#" -lt 3 ]; then
  sed -n 2,7p "$0"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${SEED_INTERNAL_TOKEN:-}" ]; then
  SEED_INTERNAL_TOKEN="$(sed -n 's/^ *SEED_INTERNAL_TOKEN *= *//p' "$ROOT/web/apps/member/.env.local" | tr -d '\r')"
fi

read -r -s -p "Password (12 or more characters): " PASSWORD
echo

EMAIL="$1" NAME="$2" ROLES="$3" MEMBER_ID="${4:-}" PASSWORD="$PASSWORD" \
  SEED_API_URL="${SEED_API_URL:-http://localhost:8080}" SEED_INTERNAL_TOKEN="$SEED_INTERNAL_TOKEN" node --input-type=module -e '
const { EMAIL, NAME, ROLES, MEMBER_ID, PASSWORD, SEED_API_URL, SEED_INTERNAL_TOKEN } = process.env;
const response = await fetch(`${SEED_API_URL}/internal/auth/staff/accounts`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${SEED_INTERNAL_TOKEN}` },
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
console.log(`Saved. Staff id: ${(await response.json()).staffId}`);
'
