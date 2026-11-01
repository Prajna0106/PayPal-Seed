#!/usr/bin/env bash
# Creates, or finds, the PayPal product and the two monthly plans Seed bills on, by calling the running
# backend (it holds the PayPal credentials). Safe to run any number of times: it creates only what is missing.
#
#   SEED_INTERNAL_TOKEN=... ./scripts/paypal-bootstrap.sh
#   SEED_API_URL=https://seed-api.onrender.com SEED_INTERNAL_TOKEN=... ./scripts/paypal-bootstrap.sh
#
# The backend needs PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET (sandbox or live) before this can work.
set -euo pipefail

api_url="${SEED_API_URL:-http://localhost:8080}"
token="${SEED_INTERNAL_TOKEN:-}"

if [ -z "$token" ]; then
  echo "SEED_INTERNAL_TOKEN is not set (the same 16+ character token the backend runs with)." >&2
  exit 1
fi

echo "Bootstrapping the PayPal billing catalog through ${api_url} ..."
reply="$(curl --silent --show-error --max-time 90 --request POST \
  --header "Authorization: Bearer ${token}" \
  --write-out $'\n%{http_code}' \
  "${api_url}/internal/paypal/catalog/bootstrap")"
status="${reply##*$'\n'}"
body="${reply%$'\n'*}"

if [ "$status" != "200" ]; then
  echo "Bootstrap failed with HTTP ${status}:" >&2
  echo "$body" >&2
  case "$status" in
    401|403) echo "Check SEED_INTERNAL_TOKEN." >&2 ;;
    409) echo "PayPal refused a create; the detail above names the issue (credentials, permissions, sandbox vs live)." >&2 ;;
    503) echo "PayPal or the backend is unreachable. Nothing half-made is left behind; run the script again." >&2 ;;
  esac
  exit 1
fi

# jq when present, a plain sed otherwise: the reply is one flat JSON object.
field() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$body" | jq -r ".$1"
  else
    printf '%s' "$body" | sed -n "s/.*\"$1\":\"\\{0,1\\}\\([^\",}]*\\)\"\\{0,1\\}.*/\\1/p"
  fi
}

product_id="$(field productId)"
savings_plan_id="$(field savingsPlanId)"
loan_plan_id="$(field loanPlanId)"

if [ -z "$product_id" ] || [ -z "$savings_plan_id" ] || [ -z "$loan_plan_id" ]; then
  echo "The reply did not contain the expected ids:" >&2
  echo "$body" >&2
  exit 1
fi

echo
echo "PayPal catalog is ready (created in this run: product=$(field createdProduct), savings plan=$(field createdSavingsPlan), loan plan=$(field createdLoanPlan))."
echo
echo "Set these for the api, ingress and worker roles (the Environment tab of seed-api, seed-ingress and seed-worker on Render), then restart them:"
echo "  SEED_PAYPAL_PRODUCT_ID=${product_id}"
echo "  SEED_PAYPAL_SAVINGS_PLAN_ID=${savings_plan_id}"
echo "  PAYPAL_LOAN_PLAN_ID=${loan_plan_id}"
echo
echo "Both plans charge \$1 per unit each month, so a subscription's quantity is the amount in whole dollars."
echo
echo "Webhook: create one in the PayPal developer dashboard for  <ingress url>/api/v1/webhooks/paypal"
echo "and set PAYPAL_WEBHOOK_ID to its id. Subscribe it to:"
echo "  BILLING.SUBSCRIPTION.*  PAYMENT.SALE.*  PAYMENT.CAPTURE.*"
echo "  PAYMENT.PAYOUTSBATCH.SUCCESS  PAYMENT.PAYOUTSBATCH.DENIED  PAYMENT.PAYOUTS-ITEM.*"
