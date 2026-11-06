"""Phase 1 Verification Suite: Seed MCP Server, Scoped Tools, PayPal Agentic Checkout & V01-V12 Guardrails."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from seed_agents.backend_client import AgentTokenContext
from seed_agents.guardrails import (
    classify_hardship_reason,
    detect_prompt_injection,
    sanitize_untrusted_text,
    wrap_untrusted_tool_output,
)
from seed_agents.mcp_server import SeedMcpServer


LOAN_ID = "0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01"
CASE_ID = "0197f3a8-1111-7000-8000-00000000c001"
RUN_ID = "0197f3a9-0000-7000-8000-00000000a001"


class Phase1McpAndGuardrailsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.mcp = SeedMcpServer()
        self.ctx = AgentTokenContext(run_id=RUN_ID, case_id=CASE_ID, bound_loan_id=LOAN_ID)

    def test_mcp_initialize_and_list_tools(self) -> None:
        init_resp = self.mcp.handle_jsonrpc({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        self.assertIsNotNone(init_resp)
        assert init_resp is not None
        self.assertEqual(init_resp["result"]["serverInfo"]["name"], "paypal-seed-mcp")

        tools_resp = self.mcp.handle_jsonrpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        assert tools_resp is not None
        tools = tools_resp["result"]["tools"]
        self.assertGreaterEqual(len(tools), 14)
        names = {t["name"] for t in tools}
        self.assertIn("get_hardship_signals", names)
        self.assertIn("preview_hardship_option", names)
        self.assertIn("simulate_catchup_payment", names)
        self.assertIn("simulate_loan_affordability", names)
        self.assertIn("simulate_tier_progression", names)
        self.assertIn("create_agentic_checkout_order", names)
        self.assertIn("submit_hardship_proposal", names)

    def test_scope_and_loan_binding_security(self) -> None:
        # 1. Asking for a different loan returns 404 loan-not-found
        other_loan = "0197f3a2-9999-7b3a-9d10-999999999999"
        resp = self.mcp.handle_jsonrpc(
            {
                "jsonrpc": "2.0",
                "id": 3,
                "method": "tools/call",
                "params": {"name": "get_loan_summary", "arguments": {"loanId": other_loan}},
            },
            ctx=self.ctx,
        )
        assert resp is not None
        self.assertTrue(resp["result"]["isError"])
        self.assertIn("404 loan-not-found", resp["result"]["content"][0]["text"])

        # 2. Token without proposal:submit scope cannot submit proposals or create checkout orders
        read_only_ctx = AgentTokenContext(
            run_id=RUN_ID,
            case_id=CASE_ID,
            bound_loan_id=LOAN_ID,
            scopes={"loan:read", "signals:read"},
        )
        resp_forbidden = self.mcp.handle_jsonrpc(
            {
                "jsonrpc": "2.0",
                "id": 4,
                "method": "tools/call",
                "params": {
                    "name": "create_agentic_checkout_order",
                    "arguments": {"loanId": LOAN_ID, "caseId": CASE_ID},
                },
            },
            ctx=read_only_ctx,
        )
        assert resp_forbidden is not None
        self.assertTrue(resp_forbidden["result"]["isError"])
        self.assertIn("403 missing-scope", resp_forbidden["result"]["content"][0]["text"])

    def test_option_d_agentic_checkout_order_creation(self) -> None:
        order = self.mcp.call_tool(
            "create_agentic_checkout_order",
            {"loanId": LOAN_ID, "caseId": CASE_ID},
            ctx=self.ctx,
        )
        # F5 §457: 9600c/wk -> ~46285c/mo * 15% = $69 (6900 cents), leaving $81 (8100 cents) shortfall on $150
        self.assertEqual(order["amountCents"], 6900)
        self.assertEqual(order["remainingShortfallCents"], 8100)
        self.assertEqual(order["shortfallStatus"], "LEAVES_SHORTFALL")
        self.assertTrue(order["requiresMemberApproval"])
        self.assertIn("checkoutnow?token=", order["approvalUrl"])

    def test_v01_v12_validator_approves_grounded_and_rejects_ungrounded(self) -> None:
        # Gather tool facts in this run first
        self.mcp.call_tool("get_hardship_signals", {"loanId": LOAN_ID}, ctx=self.ctx)
        self.mcp.call_tool("get_repayment_schedule", {"loanId": LOAN_ID}, ctx=self.ctx)
        self.mcp.call_tool("list_offered_options", {"loanId": LOAN_ID}, ctx=self.ctx)

        valid_proposal = {
            "runId": RUN_ID,
            "caseId": CASE_ID,
            "loanId": LOAN_ID,
            "agentVersion": "guardian@1.0.0",
            "promptHash": "sha256:9c1d77e0a2b34f55",
            "assessment": {
                "computedLevel": "LIKELY_MISS",
                "proposedLevel": "LIKELY_MISS",
                "escalated": False,
                "citedSignals": ["S1_INCOME_DROP", "S2_COVERAGE"],
            },
            "options": [
                {"option": "MOVE_DATE", "previewHash": "sha256:11aa22bb33cc44dd", "recommended": False},
                {"option": "PAUSE_ONE_MONTH", "previewHash": "sha256:3f9a1c0e5b7d42a8", "recommended": True},
                {"option": "EXTEND_PLAN", "previewHash": "sha256:55ee66ff77aa88bb", "recommended": False},
            ],
            "figures": [
                {"value": 52, "unit": "PERCENT", "kind": "INCOME_DROP"},
                {"value": 15000, "unit": "USD_CENTS", "kind": "INSTALMENT"},
            ],
            "narrative": {
                "officerBrief": "Weekly income dropped 52%. The $150 instalment due 2027-02-01 is at risk. A one-month pause is recommended.",
                "memberMessage": {
                    "en": "Hi, your $150 payment is due on 2027-02-01. You can move the date, pause one month, or extend the plan with $0 extra cost.",
                    "es": "Hola, tu pago de $150 vence el 2027-02-01. Puedes mover la fecha, pausar un mes o extender el plan con $0 costo extra.",
                },
            },
        }

        receipt = self.mcp.call_tool("submit_hardship_proposal", {"proposal": valid_proposal}, ctx=self.ctx)
        self.assertEqual(receipt["status"], "QUEUED_FOR_MEMBER")
        self.assertEqual(receipt["verdict"]["result"], "APPROVABLE")
        self.assertFalse(receipt["templateFallback"])

        # Now test hallucinated dollar figure ($175) and forbidden word ("penalty")
        bad_proposal = json.loads(json.dumps(valid_proposal))
        bad_proposal["narrative"]["memberMessage"]["en"] = (
            "Pay your $175 balance immediately to avoid a late penalty and protect your credit score!"
        )
        bad_receipt = self.mcp.call_tool("submit_hardship_proposal", {"proposal": bad_proposal}, ctx=self.ctx)
        self.assertEqual(bad_receipt["status"], "REJECTED")
        self.assertTrue(bad_receipt["templateFallback"])
        failed_codes = {f["code"] for f in bad_receipt["verdict"]["failed"]}
        self.assertIn("V06_UNGROUNDED_FIGURE", failed_codes)
        self.assertIn("V07_FORBIDDEN_CONTENT", failed_codes)

    def test_all_18_injection_corpus_attacks_contained(self) -> None:
        corpus_path = Path(__file__).resolve().parent.parent / "shared" / "evals" / "injection-corpus.json"
        self.assertTrue(corpus_path.exists(), f"Missing injection corpus at {corpus_path}")
        attacks = json.loads(corpus_path.read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(attacks), 18)

        for attack in attacks:
            text = attack.get("input", attack.get("payload", ""))
            res = classify_hardship_reason(text, attack.get("language", "en"))
            self.assertIn(
                res["category"],
                {"INCOME_DROP", "HOURS_CUT", "JOB_LOSS", "MEDICAL_OR_FAMILY", "UNEXPECTED_EXPENSE", "PAYDAY_TIMING", "OTHER"},
            )
            # Sanitized text must never contain raw XML breakouts or markdown code fences
            cleaned = sanitize_untrusted_text(text)
            self.assertNotIn("</member_text>", cleaned)
            self.assertNotIn("<system_override>", cleaned)
            self.assertNotIn("```", cleaned)
            fenced = wrap_untrusted_tool_output("extract_hardship_reason", res)
            self.assertTrue(fenced.startswith('<untrusted_tool_output tool="extract_hardship_reason">'))


if __name__ == "__main__":
    unittest.main()
