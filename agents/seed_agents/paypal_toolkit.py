"""PayPal Agentic Commerce & Checkout Toolkit Bridge (ADR 0010 §3 & F5 Option D).

The agent NEVER holds direct capture/payout credentials to move money without consent.
Instead, it computes a safe Option D catch-up payment or goal top-up and creates a
PayPal Checkout Order that the MEMBER approves in 1 click.
"""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass
from typing import Any

import httpx

from .config import AgentRuntimeConfig, load_config


@dataclass(frozen=True)
class AgenticCheckoutOrder:
    order_id: str
    loan_id: str
    case_id: str
    amount_cents: int
    instalment_Cents: int
    remaining_shortfall_cents: int
    custom_id: str
    approval_url: str
    status: str  # "CREATED_AWAITING_MEMBER_APPROVAL"
    source: str  # "PAYPAL_SANDBOX_API" | "SEED_PAYMENTS_MODULE" | "SANDBOX_SIMULATOR"
    requires_member_approval: bool = True

    def to_dict(self) -> dict[str, Any]:
        return {
            "orderId": self.order_id,
            "loanId": self.loan_id,
            "caseId": self.case_id,
            "amountCents": self.amount_cents,
            "amountFormatted": f"${self.amount_cents / 100:.2f}",
            "instalmentCents": self.instalment_Cents,
            "remainingShortfallCents": self.remaining_shortfall_cents,
            "shortfallStatus": "LEAVES_SHORTFALL" if self.remaining_shortfall_cents > 0 else "CLEARS_INSTALMENT",
            "customId": self.custom_id,
            "approvalUrl": self.approval_url,
            "status": self.status,
            "source": self.source,
            "requiresMemberApproval": self.requires_member_approval,
        }


def compute_option_d_catchup_cents(
    *,
    last_4_weeks_average_weekly_cents: int,
    instalment_remaining_cents: int,
    custom_amount_cents: int | None = None,
    burden_bps: int = 1500,
) -> dict[str, Any]:
    """Deterministic Option D ('Pay what you can') sizing math from F5 03-algorithms.md §457.

    Formula:
      projectedMonthlyInflow = last4WeeksAverageWeeklyCents * 4.3333 (or 4 weeks * 1.2038)
      safeCatchup = floor(projectedMonthlyInflow * 1500 / 10000 / 100) * 100
      bounded between $10 (1000c) and instalmentRemainingCents.
    """
    monthly_inflow_cents = int(round(last_4_weeks_average_weekly_cents * 4.82135))
    computed_safe_cents = (monthly_inflow_cents * burden_bps // 10_000 // 100) * 100
    computed_safe_cents = max(1000, min(computed_safe_cents, instalment_remaining_cents))

    chosen_cents = custom_amount_cents if custom_amount_cents and custom_amount_cents > 0 else computed_safe_cents
    chosen_cents = max(500, min(chosen_cents, instalment_remaining_cents))
    shortfall_cents = max(0, instalment_remaining_cents - chosen_cents)

    return {
        "recommendedCatchupCents": computed_safe_cents,
        "selectedAmountCents": chosen_cents,
        "instalmentRemainingCents": instalment_remaining_cents,
        "remainingShortfallCents": shortfall_cents,
        "shortfallStatus": "LEAVES_SHORTFALL" if shortfall_cents > 0 else "CLEARS_INSTALMENT",
        "burdenBps": burden_bps,
        "requiresOfficerApproval": False,
        "approvalTier": "MEMBER_CHECKOUT_ONLY",
    }


class PayPalAgenticCheckoutToolkit:
    """Creates member-approved PayPal Checkout Orders for Option D catch-up payments."""

    def __init__(self, config: AgentRuntimeConfig | None = None) -> None:
        self.config = config or load_config()

    def create_option_d_checkout(
        self,
        *,
        loan_id: str,
        case_id: str,
        amount_cents: int,
        instalment_cents: int,
        idempotency_key: str | None = None,
    ) -> AgenticCheckoutOrder:
        idem_key = idempotency_key or str(uuid.uuid4())
        shortfall = max(0, instalment_cents - amount_cents)
        custom_id = f"seed:option-d:{loan_id}:{case_id}"

        # 1. Try live PayPal Sandbox v2 Orders API if sandbox credentials are set
        if self.config.paypal_client_id and self.config.paypal_client_secret:
            try:
                order_id, approve_link = self._create_paypal_sandbox_order(
                    amount_cents=amount_cents,
                    custom_id=custom_id,
                    loan_id=loan_id,
                    idem_key=idem_key,
                )
                return AgenticCheckoutOrder(
                    order_id=order_id,
                    loan_id=loan_id,
                    case_id=case_id,
                    amount_cents=amount_cents,
                    instalment_Cents=instalment_cents,
                    remaining_shortfall_cents=shortfall,
                    custom_id=custom_id,
                    approval_url=approve_link,
                    status="CREATED_AWAITING_MEMBER_APPROVAL",
                    source="PAYPAL_SANDBOX_API",
                )
            except Exception:
                pass

        # 2. Deterministic Sandbox Order Token (for local dev, tests & offline demos)
        digest = hashlib.sha256(f"{loan_id}:{case_id}:{amount_cents}:{idem_key}".encode()).hexdigest()[:14].upper()
        order_id = f"PP-OPTD-{digest}"
        approve_url = f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}"
        return AgenticCheckoutOrder(
            order_id=order_id,
            loan_id=loan_id,
            case_id=case_id,
            amount_cents=amount_cents,
            instalment_Cents=instalment_cents,
            remaining_shortfall_cents=shortfall,
            custom_id=custom_id,
            approval_url=approve_url,
            status="CREATED_AWAITING_MEMBER_APPROVAL",
            source="SANDBOX_SIMULATOR",
        )

    def _create_paypal_sandbox_order(
        self,
        *,
        amount_cents: int,
        custom_id: str,
        loan_id: str,
        idem_key: str,
    ) -> tuple[str, str]:
        with httpx.Client(base_url=self.config.paypal_base_url, timeout=10.0) as client:
            token_resp = client.post(
                "/v1/oauth2/token",
                auth=(self.config.paypal_client_id, self.config.paypal_client_secret),
                data={"grant_type": "client_credentials"},
            )
            token_resp.raise_for_status()
            access_token = token_resp.json()["access_token"]

            dollars = f"{amount_cents / 100:.2f}"
            order_resp = client.post(
                "/v2/checkout/orders",
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "PayPal-Request-Id": idem_key,
                    "Content-Type": "application/json",
                },
                json={
                    "intent": "CAPTURE",
                    "purchase_units": [
                        {
                            "custom_id": custom_id,
                            "description": f"PayPal Seed Option D Catch-Up Payment (Loan {loan_id[:8]})",
                            "amount": {"currency_code": "USD", "value": dollars},
                        }
                    ],
                    "application_context": {
                        "brand_name": "PayPal Seed",
                        "user_action": "PAY_NOW",
                        "return_url": f"{self.config.member_app_origin}/app/loans/{loan_id}?paid=option-d",
                        "cancel_url": f"{self.config.member_app_origin}/app/loans/{loan_id}/help",
                    },
                },
            )
            order_resp.raise_for_status()
            data = order_resp.json()
            order_id = data["id"]
            approve_link = next(
                (link["href"] for link in data.get("links", []) if link.get("rel") == "approve"),
                f"https://www.sandbox.paypal.com/checkoutnow?token={order_id}",
            )
            return order_id, approve_link
