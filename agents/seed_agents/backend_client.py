"""Scoped Tool Client & Deterministic Fintech Simulation Engine for PayPal Seed."""

from __future__ import annotations

import hashlib
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx

from .config import AgentRuntimeConfig, load_config
from .guardrails import validate_proposal_v01_v12
from .paypal_toolkit import PayPalAgenticCheckoutToolkit, compute_option_d_catchup_cents


# Credit Tier Ladder (F2 & F6)
TIER_LADDER: list[dict[str, Any]] = [
    {"tier": "NEW", "limitCents": 0, "requiredStreakMonths": 0, "requiredOnTimeRepayments": 0},
    {"tier": "BUILDING", "limitCents": 20000, "requiredStreakMonths": 3, "requiredOnTimeRepayments": 0},
    {"tier": "STEADY", "limitCents": 50000, "requiredStreakMonths": 4, "requiredOnTimeRepayments": 3},
    {"tier": "TRUSTED", "limitCents": 100000, "requiredStreakMonths": 6, "requiredOnTimeRepayments": 6},
    {"tier": "EXEMPLARY", "limitCents": 200000, "requiredStreakMonths": 9, "requiredOnTimeRepayments": 12},
]


@dataclass
class AgentTokenContext:
    """Scoped, short-lived capability token context bound to a run and optional loan."""

    run_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    case_id: str = "0197f3a8-1111-7000-8000-00000000c001"
    bound_loan_id: str | None = None
    token: str | None = None
    scopes: set[str] = field(
        default_factory=lambda: {
            "loan:read",
            "schedule:read",
            "signals:read",
            "income:read",
            "preview:run",
            "desk:read",
            "proposal:submit",
        }
    )

    def assert_scope(self, required_scope: str, requested_loan_id: str | None = None) -> None:
        if required_scope not in self.scopes:
            raise PermissionError(f"403 missing-scope: token lacks required scope '{required_scope}'")
        if self.bound_loan_id and requested_loan_id and str(requested_loan_id) != str(self.bound_loan_id):
            # Per ADR 0003 & F5 §4: asking for any other loan returns 404 (never confirms loan existence)
            raise LookupError("404 loan-not-found: token is not bound to the requested loanId")


class SeedToolGateway:
    """Executes all 14 MCP & Agent tools against the Java Backend with deterministic engine fallback."""

    def __init__(self, config: AgentRuntimeConfig | None = None) -> None:
        self.config = config or load_config()
        self.paypal = PayPalAgenticCheckoutToolkit(self.config)
        self._tool_history: dict[str, dict[str, Any]] = {}

    def record_output(self, run_id: str, tool_name: str, result: dict[str, Any]) -> dict[str, Any]:
        self._tool_history.setdefault(run_id, {})[tool_name] = result
        return result

    def get_run_outputs(self, run_id: str) -> dict[str, Any]:
        return dict(self._tool_history.get(run_id, {}))

    def _try_java_get(self, path: str, params: dict[str, Any], ctx: AgentTokenContext) -> dict[str, Any] | None:
        if not ctx.token:
            return None
        try:
            with httpx.Client(base_url=self.config.seed_api_url, timeout=3.0) as client:
                resp = client.get(path, params=params, headers={"Authorization": f"Bearer {ctx.token}"})
                if resp.status_code == 200:
                    return resp.json()
        except Exception:
            pass
        return None

    def _try_java_post(
        self, path: str, body: dict[str, Any], ctx: AgentTokenContext, headers: dict[str, str] | None = None
    ) -> dict[str, Any] | None:
        if not ctx.token:
            return None
        try:
            req_headers = {"Authorization": f"Bearer {ctx.token}"}
            if headers:
                req_headers.update(headers)
            with httpx.Client(base_url=self.config.seed_api_url, timeout=4.0) as client:
                resp = client.post(path, json=body, headers=req_headers)
                if resp.status_code in (200, 201, 202):
                    return resp.json()
        except Exception:
            pass
        return None

    # ------------------------------------------------------------------
    # 1. Read Tools (F5 Guardian & Hardship)
    # ------------------------------------------------------------------

    def get_loan_summary(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("loan:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/loan-summary", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "memberRef": "m_7Qk2xP",
            "principalCents": 120000,
            "outstandingCents": 90000,
            "status": "ACTIVE",
            "bucket": "CURRENT",
            "dpd": 0,
            "instalmentCount": 8,
            "paidInstalments": 2,
            "collectionMode": "AUTOPAY",
            "businessDate": "2027-01-18",
        }
        return self.record_output(ctx.run_id, "get_loan_summary", result)

    def get_repayment_schedule(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("schedule:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/repayment-schedule", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "version": 1,
            "reason": "ORIGINAL",
            "businessDate": "2027-01-18",
            "instalments": [
                {"seq": 3, "dueDate": "2027-02-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
                {"seq": 4, "dueDate": "2027-03-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
                {"seq": 5, "dueDate": "2027-04-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
                {"seq": 6, "dueDate": "2027-05-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
                {"seq": 7, "dueDate": "2027-06-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
                {"seq": 8, "dueDate": "2027-07-01", "amountCents": 15000, "paidCents": 0, "remainingCents": 15000},
            ],
        }
        return self.record_output(ctx.run_id, "get_repayment_schedule", result)

    def get_loan_aging(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("loan:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/loan-aging", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "bucket": "CURRENT",
            "dpd": 0,
            "nextInstalment": {"seq": 3, "dueDate": "2027-02-01", "amountCents": 15000, "daysUntilDue": 14},
            "graceEndsOn": "2027-02-06",
            "missedInstalments": 0,
        }
        return self.record_output(ctx.run_id, "get_loan_aging", result)

    def get_hardship_signals(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("signals:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/hardship-signals", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "policyVersion": "hardship-v1",
            "computedLevel": "LIKELY_MISS",
            "points": 7,
            "signals": [
                {
                    "code": "S1_INCOME_DROP",
                    "points": 5,
                    "evidence": {
                        "recentWeeklyInflowCents": 9600,
                        "medianWeeklyInflowCents": 20000,
                        "dropPercent": 52,
                    },
                },
                {
                    "code": "S2_COVERAGE",
                    "points": 2,
                    "evidence": {
                        "projectedInflowBeforeDueCents": 12000,
                        "instalmentCents": 15000,
                        "coverageRatioPercent": 80,
                    },
                },
            ],
            "suppressed": False,
        }
        return self.record_output(ctx.run_id, "get_hardship_signals", result)

    def get_income_summary(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("income:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/income-summary", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "windowDays": 90,
            "medianWeeklyInflowCents": 20000,
            "last4WeeksAverageCents": 9600,
            "usualPaydayWeekday": "FRIDAY",
            "lastInflowDaysAgo": 6,
            "weeklyInflowCents": [21000, 19000, 20000, 22000, 20000, 18000, 21000, 20000, 19000, 10000, 9000, 9600],
        }
        return self.record_output(ctx.run_id, "get_income_summary", result)

    def list_offered_options(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("preview:run", loan_id)
        live = self._try_java_get("/agent/v1/tools/offered-options", {"loanId": loan_id}, ctx)
        result = live or {
            "loanId": loan_id,
            "options": [
                {
                    "option": "MOVE_DATE",
                    "primitive": "MOVE_DATE",
                    "seq": 3,
                    "days": 7,
                    "newDueDate": "2027-02-08",
                    "previewHash": "sha256:11aa22bb33cc44dd",
                    "eligible": True,
                    "approvalTier": "AUTO_POLICY",
                    "recommended": False,
                },
                {
                    "option": "PAUSE_ONE_MONTH",
                    "primitive": "DEFER_TO_END",
                    "seq": 3,
                    "newLastDueDate": "2027-08-01",
                    "previewHash": "sha256:3f9a1c0e5b7d42a8",
                    "eligible": True,
                    "approvalTier": "OFFICER_AND_CHECKER",
                    "recommended": True,
                },
                {
                    "option": "EXTEND_PLAN",
                    "primitive": "REAMORTIZE",
                    "extraMonths": 3,
                    "newInstalmentCents": 10000,
                    "previewHash": "sha256:55ee66ff77aa88bb",
                    "eligible": True,
                    "approvalTier": "OFFICER_AND_CHECKER",
                    "recommended": False,
                },
            ],
        }
        return self.record_output(ctx.run_id, "list_offered_options", result)

    def preview_hardship_option(
        self,
        *,
        loan_id: str,
        option: str,
        ctx: AgentTokenContext,
        seq: int = 3,
        days: int = 7,
        extra_months: int = 3,
    ) -> dict[str, Any]:
        ctx.assert_scope("preview:run", loan_id)
        body = {"loanId": loan_id, "option": option, "seq": seq, "days": days, "extraMonths": extra_months}
        live = self._try_java_post("/agent/v1/tools/preview-option", body, ctx)
        if live:
            return self.record_output(ctx.run_id, f"preview_option:{option}", live)

        presets: dict[str, dict[str, Any]] = {
            "MOVE_DATE": {
                "option": "MOVE_DATE",
                "primitive": "MOVE_DATE",
                "previewHash": "sha256:11aa22bb33cc44dd",
                "eligible": True,
                "approvalTier": "AUTO_POLICY",
                "removed": [{"seq": seq, "dueDate": "2027-02-01", "amountCents": 15000}],
                "added": [{"seq": seq, "dueDate": "2027-02-08", "amountCents": 15000}],
                "newLastDueDate": "2027-07-01",
                "totalInstalmentsAfter": 8,
                "delayDays": days,
                "recommended": False,
                "zeroOfficerIntervention": True,
            },
            "PAUSE_ONE_MONTH": {
                "option": "PAUSE_ONE_MONTH",
                "primitive": "DEFER_TO_END",
                "previewHash": "sha256:3f9a1c0e5b7d42a8",
                "eligible": True,
                "approvalTier": "OFFICER_AND_CHECKER",
                "removed": [{"seq": seq, "dueDate": "2027-02-01", "amountCents": 15000}],
                "added": [{"seq": 9, "dueDate": "2027-08-01", "amountCents": 15000}],
                "newLastDueDate": "2027-08-01",
                "totalInstalmentsAfter": 8,
                "delayDays": 31,
                "recommended": True,
                "zeroOfficerIntervention": False,
            },
            "EXTEND_PLAN": {
                "option": "EXTEND_PLAN",
                "primitive": "REAMORTIZE",
                "previewHash": "sha256:55ee66ff77aa88bb",
                "eligible": True,
                "approvalTier": "OFFICER_AND_CHECKER",
                "newInstalmentCents": 10000,
                "newLastDueDate": "2027-10-01",
                "totalInstalmentsAfter": 11,
                "delayDays": 90,
                "recommended": False,
                "zeroOfficerIntervention": False,
            },
        }
        if option not in presets:
            raise ValueError(f"Unsupported hardship option '{option}'")
        result = presets[option]
        return self.record_output(ctx.run_id, f"preview_option:{option}", result)

    def list_prior_cases(self, loan_id: str, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("signals:read", loan_id)
        live = self._try_java_get("/agent/v1/tools/prior-cases", {"loanId": loan_id}, ctx)
        result = live or {"loanId": loan_id, "cases": []}
        return self.record_output(ctx.run_id, "list_prior_cases", result)

    # ------------------------------------------------------------------
    # 2. Simulation Tools (Sandbox Math for Option D, Underwriting & Trust)
    # ------------------------------------------------------------------

    def simulate_catchup_payment(
        self,
        *,
        loan_id: str,
        ctx: AgentTokenContext,
        custom_amount_cents: int | None = None,
    ) -> dict[str, Any]:
        ctx.assert_scope("preview:run", loan_id)
        income = self.get_income_summary(loan_id, ctx)
        schedule = self.get_repayment_schedule(loan_id, ctx)
        next_inst = (schedule.get("instalments") or [{"remainingCents": 15000}])[0]
        sim = compute_option_d_catchup_cents(
            last_4_weeks_average_weekly_cents=int(income.get("last4WeeksAverageCents", 9600)),
            instalment_remaining_cents=int(next_inst.get("remainingCents", 15000)),
            custom_amount_cents=custom_amount_cents,
        )
        sim["loanId"] = loan_id
        sim["dueDate"] = next_inst.get("dueDate", "2027-02-01")
        return self.record_output(ctx.run_id, "simulate_catchup_payment", sim)

    def simulate_loan_affordability(
        self,
        *,
        requested_amount_cents: int,
        term_months: int,
        lowest_monthly_income_cents: int,
        average_monthly_income_cents: int,
        saving_streak_months: int,
        ctx: AgentTokenContext,
    ) -> dict[str, Any]:
        ctx.assert_scope("preview:run")
        term = max(1, min(term_months, 12))
        monthly_instalment = requested_amount_cents // term
        max_safe_instalment = (lowest_monthly_income_cents * 1500) // 10_000  # 15% of lowest month
        burden_bps = (
            (monthly_instalment * 10_000) // lowest_monthly_income_cents if lowest_monthly_income_cents > 0 else 10000
        )
        affordable = monthly_instalment <= max_safe_instalment and saving_streak_months >= 3

        # Round safe counter-offer to nearest $25 (2500 cents)
        raw_safe_total = max_safe_instalment * term
        safe_amount_cents = min(requested_amount_cents, (raw_safe_total // 2500) * 2500)
        safe_instalment_cents = safe_amount_cents // term

        result = {
            "requestedAmountCents": requested_amount_cents,
            "termMonths": term,
            "monthlyInstalmentCents": monthly_instalment,
            "lowestMonthlyIncomeCents": lowest_monthly_income_cents,
            "averageMonthlyIncomeCents": average_monthly_income_cents,
            "savingStreakMonths": saving_streak_months,
            "worstMonthBurdenBps": burden_bps,
            "maxAllowedBurdenBps": 1500,
            "maxSafeInstalmentCents": max_safe_instalment,
            "affordableAtRequestedAmount": affordable,
            "recommendedAmountCents": requested_amount_cents if affordable else safe_amount_cents,
            "recommendedInstalmentCents": monthly_instalment if affordable else safe_instalment_cents,
            "outcome": "RECOMMEND_FULL" if affordable else ("RECOMMEND_REDUCED" if safe_amount_cents >= 5000 else "DEFER"),
        }
        return self.record_output(ctx.run_id, "simulate_loan_affordability", result)

    def simulate_tier_progression(
        self,
        *,
        current_tier: str,
        streak_months: int,
        on_time_repayments_count: int,
        monthly_goal_cents: int,
        usual_payday_weekday: str = "FRIDAY",
        current_debit_weekday: str = "TUESDAY",
        ctx: AgentTokenContext,
    ) -> dict[str, Any]:
        ctx.assert_scope("preview:run")
        tier_upper = current_tier.upper()
        idx = next((i for i, t in enumerate(TIER_LADDER) if t["tier"] == tier_upper), 0)
        current_entry = TIER_LADDER[idx]
        next_entry = TIER_LADDER[min(idx + 1, len(TIER_LADDER) - 1)]

        months_needed = max(0, next_entry["requiredStreakMonths"] - streak_months)
        repayments_needed = max(0, next_entry["requiredOnTimeRepayments"] - on_time_repayments_count)
        payday_mismatch = usual_payday_weekday.upper() != current_debit_weekday.upper()

        result = {
            "currentTier": current_entry["tier"],
            "currentLimitCents": current_entry["limitCents"],
            "nextTier": next_entry["tier"],
            "nextLimitCents": next_entry["limitCents"],
            "streakMonths": streak_months,
            "onTimeRepaymentsCount": on_time_repayments_count,
            "additionalStreakMonthsNeeded": months_needed,
            "additionalOnTimeRepaymentsNeeded": repayments_needed,
            "monthlyGoalCents": monthly_goal_cents,
            "paydayMismatchDetected": payday_mismatch,
            "usualPaydayWeekday": usual_payday_weekday.upper(),
            "currentDebitWeekday": current_debit_weekday.upper(),
            "recommendedAction": (
                f"SHIFT_DEBIT_TO_{usual_payday_weekday.upper()}" if payday_mismatch else "MAINTAIN_CADENCE"
            ),
        }
        return self.record_output(ctx.run_id, "simulate_tier_progression", result)

    # ------------------------------------------------------------------
    # 3. Desk & Portfolio Tools (Officer Copilot)
    # ------------------------------------------------------------------

    def query_portfolio_health(self, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("desk:read")
        result = {
            "asOfBusinessDate": "2027-01-18",
            "activeLoansCount": 142,
            "totalOutstandingCents": 8450000,
            "onTimeRepaymentRateBps": 9640,
            "membersServedWithoutCreditScore": 142,
            "agingBuckets": {
                "CURRENT": 124,
                "GRACE_1_5_DPD": 11,
                "LATE_6_30_DPD": 5,
                "LATE_31_60_DPD": 2,
                "LATE_60_PLUS_DPD": 0,
            },
            "hardshipCases": {
                "activeCases": 9,
                "autoApprovedOptionA": 5,
                "awaitingMakerChecker": 3,
                "optionDCatchupPending": 1,
            },
        }
        return self.record_output(ctx.run_id, "query_portfolio_health", result)

    def query_sla_queue(self, ctx: AgentTokenContext) -> dict[str, Any]:
        ctx.assert_scope("desk:read")
        result = {
            "slaTargetHours": 24,
            "itemsNearBreach": [
                {
                    "itemId": "app_0197f3b1",
                    "kind": "LOAN_APPLICATION",
                    "requestedAmountCents": 30000,
                    "savingStreakMonths": 4,
                    "assessmentOutcome": "RECOMMEND_FULL",
                    "hoursRemainingToSla": 2.5,
                    "preVerifiedByRules": True,
                    "recommendedOfficerAction": "ONE_CLICK_APPROVE",
                },
                {
                    "itemId": "hc_0197f3a8",
                    "kind": "HARDSHIP_MAKER_CHECKER",
                    "option": "PAUSE_ONE_MONTH",
                    "loanId": "0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01",
                    "hoursRemainingToSla": 4.0,
                    "preVerifiedByRules": True,
                    "recommendedOfficerAction": "ONE_CLICK_CHECKER_SIGN",
                },
                {
                    "itemId": "fc_0197f3c9",
                    "kind": "FAIR_CHECK_DISPUTE",
                    "requestedAmountCents": 50000,
                    "recommendedAmountCents": 35000,
                    "hoursRemainingToSla": 5.5,
                    "preVerifiedByRules": False,
                    "recommendedOfficerAction": "REVIEW_DISPUTE_EVIDENCE",
                },
            ],
        }
        return self.record_output(ctx.run_id, "query_sla_queue", result)

    # ------------------------------------------------------------------
    # 4. Guarded Action Tools (Proposals & Agentic Checkout)
    # ------------------------------------------------------------------

    def submit_hardship_proposal(
        self,
        *,
        proposal: dict[str, Any],
        ctx: AgentTokenContext,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        ctx.assert_scope("proposal:submit", proposal.get("loanId"))
        idem_key = idempotency_key or ctx.run_id

        # Run local V01-V12 deterministic validation
        loan_id = str(proposal.get("loanId", ctx.bound_loan_id or "0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01"))
        signals = self._tool_history.get(ctx.run_id, {}).get("get_hardship_signals") or self.get_hardship_signals(
            loan_id, ctx
        )
        offered = (
            self._tool_history.get(ctx.run_id, {}).get("list_offered_options") or self.list_offered_options(loan_id, ctx)
        ).get("options", [])

        verdict = validate_proposal_v01_v12(
            proposal,
            expected_run_id=str(proposal.get("runId", ctx.run_id)),
            expected_case_id=str(proposal.get("caseId", ctx.case_id)),
            expected_loan_id=loan_id,
            computed_signals=signals,
            offered_options=offered,
            tool_outputs=self.get_run_outputs(ctx.run_id),
        )

        # Also forward to Java backend if token is active
        live = self._try_java_post("/agent/v1/proposals", proposal, ctx, headers={"Idempotency-Key": idem_key})
        if live:
            return self.record_output(ctx.run_id, "submit_hardship_proposal", live)

        receipt = {
            "proposalId": str(uuid.uuid5(uuid.NAMESPACE_OID, f"prop:{idem_key}")),
            "status": "QUEUED_FOR_MEMBER" if verdict.passed else "REJECTED",
            "verdict": {
                "result": verdict.result,
                "checks": verdict.checks,
                "failed": [{"code": f.code, "detail": f.detail} for f in verdict.failed],
            },
            "templateFallback": not verdict.passed,
        }
        return self.record_output(ctx.run_id, "submit_hardship_proposal", receipt)

    def create_agentic_checkout_order(
        self,
        *,
        loan_id: str,
        case_id: str,
        amount_cents: int | None = None,
        ctx: AgentTokenContext,
    ) -> dict[str, Any]:
        ctx.assert_scope("proposal:submit", loan_id)
        sim = self.simulate_catchup_payment(loan_id=loan_id, ctx=ctx, custom_amount_cents=amount_cents)
        order = self.paypal.create_option_d_checkout(
            loan_id=loan_id,
            case_id=case_id,
            amount_cents=int(sim["selectedAmountCents"]),
            instalment_cents=int(sim["instalmentRemainingCents"]),
            idempotency_key=ctx.run_id,
        )
        result = order.to_dict()
        return self.record_output(ctx.run_id, "create_agentic_checkout_order", result)

    def propose_goal_adjustment(
        self,
        *,
        goal_id: str,
        new_monthly_amount_cents: int,
        new_debit_weekday: str,
        reason: str,
        ctx: AgentTokenContext,
    ) -> dict[str, Any]:
        ctx.assert_scope("proposal:submit")
        digest = hashlib.sha256(f"{goal_id}:{new_monthly_amount_cents}:{new_debit_weekday}".encode()).hexdigest()[:12]
        result = {
            "proposalId": f"goal-adj-{digest}",
            "goalId": goal_id,
            "newMonthlyAmountCents": new_monthly_amount_cents,
            "newDebitWeekday": new_debit_weekday.upper(),
            "reason": reason,
            "streaksProtected": True,
            "status": "AWAITING_ONE_CLICK_MEMBER_CONFIRMATION",
        }
        return self.record_output(ctx.run_id, "propose_goal_adjustment", result)
