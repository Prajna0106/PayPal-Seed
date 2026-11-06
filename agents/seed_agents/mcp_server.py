"""PayPal Seed Model Context Protocol (MCP) Server (`paypal-seed-mcp`).

Exposes 14 scoped fintech read, simulation, and guarded proposal tools over:
  1. Standard MCP JSON-RPC 2.0 `stdio` transport (`python -m seed_agents.mcp_server`)
  2. Standard MCP JSON-RPC 2.0 HTTP endpoint (`POST /mcp` on port 8090)
  3. Direct Python invocation for the 4 LangGraph/ReAct Autonomous Agents
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from typing import Any, Callable

from .backend_client import AgentTokenContext, SeedToolGateway, TIER_LADDER
from .config import AgentRuntimeConfig, load_config
from .guardrails import classify_hardship_reason, wrap_untrusted_tool_output


@dataclass(frozen=True)
class McpToolSpec:
    name: str
    description: str
    scope: str
    read_only: bool
    input_schema: dict[str, Any]
    handler: Callable[[SeedToolGateway, dict[str, Any], AgentTokenContext], dict[str, Any]]


def _build_tool_catalogue() -> list[McpToolSpec]:
    loan_id_prop = {"loanId": {"type": "string", "description": "UUID of the target loan"}}

    return [
        McpToolSpec(
            name="get_loan_summary",
            description="Fetch pseudonymous loan summary (principal, outstandingCents, status, bucket, dpd). Scope: loan:read.",
            scope="loan:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.get_loan_summary(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="get_repayment_schedule",
            description="Read current repayment schedule version and instalment lines. Scope: schedule:read.",
            scope="schedule:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.get_repayment_schedule(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="get_loan_aging",
            description="Read days past due (dpd), aging bucket, next instalment and 5-day grace window. Scope: loan:read.",
            scope="loan:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.get_loan_aging(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="get_hardship_signals",
            description="Read deterministic scorecard signals (S1_INCOME_DROP, S2_COVERAGE) and computed risk level. Scope: signals:read.",
            scope="signals:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.get_hardship_signals(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="get_income_summary",
            description="Read consented 90-day PayPal inflow medians, last 4-week average and usual payday weekday. Scope: income:read.",
            scope="income:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.get_income_summary(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="list_offered_options",
            description="List pre-computed hardship reschedule options with previewHashes and OptionRanker recommendation. Scope: preview:run.",
            scope="preview:run",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.list_offered_options(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="preview_hardship_option",
            description="Simulate a schedule change (MOVE_DATE, PAUSE_ONE_MONTH, EXTEND_PLAN) without writing state. Scope: preview:run.",
            scope="preview:run",
            read_only=True,
            input_schema={
                "type": "object",
                "required": ["loanId", "option"],
                "properties": {
                    "loanId": {"type": "string"},
                    "option": {"type": "string", "enum": ["MOVE_DATE", "PAUSE_ONE_MONTH", "EXTEND_PLAN"]},
                    "seq": {"type": "integer", "default": 3},
                    "days": {"type": "integer", "default": 7},
                    "extraMonths": {"type": "integer", "default": 3},
                },
            },
            handler=lambda gw, args, ctx: gw.preview_hardship_option(
                loan_id=str(args["loanId"]),
                option=str(args["option"]),
                ctx=ctx,
                seq=int(args.get("seq", 3)),
                days=int(args.get("days", 7)),
                extra_months=int(args.get("extraMonths", 3)),
            ),
        ),
        McpToolSpec(
            name="list_prior_cases",
            description="List earlier hardship cases on this loan to enforce policy caps. Scope: signals:read.",
            scope="signals:read",
            read_only=True,
            input_schema={"type": "object", "required": ["loanId"], "properties": loan_id_prop},
            handler=lambda gw, args, ctx: gw.list_prior_cases(str(args["loanId"]), ctx),
        ),
        McpToolSpec(
            name="simulate_catchup_payment",
            description="Calculate Option D ('Pay what you can today') safe catch-up amount (15% burden rule) and shortfall. Scope: preview:run.",
            scope="preview:run",
            read_only=True,
            input_schema={
                "type": "object",
                "required": ["loanId"],
                "properties": {
                    "loanId": {"type": "string"},
                    "customAmountCents": {"type": "integer"},
                },
            },
            handler=lambda gw, args, ctx: gw.simulate_catchup_payment(
                loan_id=str(args["loanId"]),
                ctx=ctx,
                custom_amount_cents=int(args["customAmountCents"]) if args.get("customAmountCents") else None,
            ),
        ),
        McpToolSpec(
            name="simulate_loan_affordability",
            description="Stress-test a requested microloan against worst-month consented PayPal income and 15% burden cap. Scope: preview:run.",
            scope="preview:run",
            read_only=True,
            input_schema={
                "type": "object",
                "required": ["requestedAmountCents", "termMonths", "lowestMonthlyIncomeCents"],
                "properties": {
                    "requestedAmountCents": {"type": "integer"},
                    "termMonths": {"type": "integer"},
                    "lowestMonthlyIncomeCents": {"type": "integer"},
                    "averageMonthlyIncomeCents": {"type": "integer"},
                    "savingStreakMonths": {"type": "integer", "default": 3},
                },
            },
            handler=lambda gw, args, ctx: gw.simulate_loan_affordability(
                requested_amount_cents=int(args["requestedAmountCents"]),
                term_months=int(args["termMonths"]),
                lowest_monthly_income_cents=int(args["lowestMonthlyIncomeCents"]),
                average_monthly_income_cents=int(
                    args.get("averageMonthlyIncomeCents", args["lowestMonthlyIncomeCents"])
                ),
                saving_streak_months=int(args.get("savingStreakMonths", 3)),
                ctx=ctx,
            ),
        ),
        McpToolSpec(
            name="simulate_tier_progression",
            description="Simulate saving streak and repayment milestones to unlock the next credit record tier. Scope: preview:run.",
            scope="preview:run",
            read_only=True,
            input_schema={
                "type": "object",
                "required": ["currentTier", "streakMonths", "onTimeRepaymentsCount"],
                "properties": {
                    "currentTier": {
                        "type": "string",
                        "enum": ["NEW", "BUILDING", "STEADY", "TRUSTED", "EXEMPLARY"],
                    },
                    "streakMonths": {"type": "integer"},
                    "onTimeRepaymentsCount": {"type": "integer"},
                    "monthlyGoalCents": {"type": "integer", "default": 2500},
                    "usualPaydayWeekday": {"type": "string", "default": "FRIDAY"},
                    "currentDebitWeekday": {"type": "string", "default": "TUESDAY"},
                },
            },
            handler=lambda gw, args, ctx: gw.simulate_tier_progression(
                current_tier=str(args["currentTier"]),
                streak_months=int(args["streakMonths"]),
                on_time_repayments_count=int(args["onTimeRepaymentsCount"]),
                monthly_goal_cents=int(args.get("monthlyGoalCents", 2500)),
                usual_payday_weekday=str(args.get("usualPaydayWeekday", "FRIDAY")),
                current_debit_weekday=str(args.get("currentDebitWeekday", "TUESDAY")),
                ctx=ctx,
            ),
        ),
        McpToolSpec(
            name="query_portfolio_health",
            description="Query portfolio aging buckets, active loans, hardship conversion and inclusion metrics. Scope: desk:read.",
            scope="desk:read",
            read_only=True,
            input_schema={"type": "object", "properties": {}},
            handler=lambda gw, _args, ctx: gw.query_portfolio_health(ctx),
        ),
        McpToolSpec(
            name="query_sla_queue",
            description="Query open loan applications, fair-check disputes and maker-checker approvals near 24h SLA breach. Scope: desk:read.",
            scope="desk:read",
            read_only=True,
            input_schema={"type": "object", "properties": {}},
            handler=lambda gw, _args, ctx: gw.query_sla_queue(ctx),
        ),
        McpToolSpec(
            name="submit_hardship_proposal",
            description="Submit a figure-cited HardshipProposal to the deterministic V01-V12 validator. Scope: proposal:submit.",
            scope="proposal:submit",
            read_only=False,
            input_schema={
                "type": "object",
                "required": ["proposal"],
                "properties": {
                    "proposal": {"type": "object"},
                },
            },
            handler=lambda gw, args, ctx: gw.submit_hardship_proposal(proposal=dict(args["proposal"]), ctx=ctx),
        ),
        McpToolSpec(
            name="create_agentic_checkout_order",
            description="Create a PayPal Catch-Up Checkout Order (Option D) for 1-click member approval. Scope: proposal:submit.",
            scope="proposal:submit",
            read_only=False,
            input_schema={
                "type": "object",
                "required": ["loanId", "caseId"],
                "properties": {
                    "loanId": {"type": "string"},
                    "caseId": {"type": "string"},
                    "amountCents": {"type": "integer"},
                },
            },
            handler=lambda gw, args, ctx: gw.create_agentic_checkout_order(
                loan_id=str(args["loanId"]),
                case_id=str(args["caseId"]),
                amount_cents=int(args["amountCents"]) if args.get("amountCents") else None,
                ctx=ctx,
            ),
        ),
        McpToolSpec(
            name="propose_goal_adjustment",
            description="Prepare a 1-click savings goal schedule/amount adjustment to protect a member's saving streak. Scope: proposal:submit.",
            scope="proposal:submit",
            read_only=False,
            input_schema={
                "type": "object",
                "required": ["goalId", "newMonthlyAmountCents", "newDebitWeekday", "reason"],
                "properties": {
                    "goalId": {"type": "string"},
                    "newMonthlyAmountCents": {"type": "integer"},
                    "newDebitWeekday": {"type": "string"},
                    "reason": {"type": "string"},
                },
            },
            handler=lambda gw, args, ctx: gw.propose_goal_adjustment(
                goal_id=str(args["goalId"]),
                new_monthly_amount_cents=int(args["newMonthlyAmountCents"]),
                new_debit_weekday=str(args["newDebitWeekday"]),
                reason=str(args["reason"]),
                ctx=ctx,
            ),
        ),
        McpToolSpec(
            name="extract_hardship_reason",
            description="Extract a closed-set hardship category from untrusted member free text with prompt-injection stripping.",
            scope="signals:read",
            read_only=True,
            input_schema={
                "type": "object",
                "required": ["text"],
                "properties": {
                    "text": {"type": "string"},
                    "language": {"type": "string", "enum": ["en", "es"], "default": "en"},
                },
            },
            handler=lambda _gw, args, _ctx: classify_hardship_reason(
                str(args["text"]), str(args.get("language", "en"))
            ),
        ),
    ]


class SeedMcpServer:
    """Standard Model Context Protocol (MCP) Server for PayPal Seed."""

    SERVER_NAME = "paypal-seed-mcp"
    SERVER_VERSION = "1.0.0"
    PROTOCOL_VERSION = "2024-11-05"

    def __init__(self, config: AgentRuntimeConfig | None = None) -> None:
        self.config = config or load_config()
        self.gateway = SeedToolGateway(self.config)
        self._specs: dict[str, McpToolSpec] = {spec.name: spec for spec in _build_tool_catalogue()}

    def list_tools(self) -> list[dict[str, Any]]:
        return [
            {
                "name": spec.name,
                "description": spec.description,
                "inputSchema": spec.input_schema,
                "annotations": {
                    "readOnlyHint": spec.read_only,
                    "requiredScope": spec.scope,
                },
            }
            for spec in self._specs.values()
        ]

    def as_openai_tools(self, allowed_names: set[str] | None = None) -> list[dict[str, Any]]:
        """Formats the MCP tool catalogue for OpenAI/OpenRouter tool-calling loops."""
        out: list[dict[str, Any]] = []
        for spec in self._specs.values():
            if allowed_names is not None and spec.name not in allowed_names:
                continue
            out.append(
                {
                    "type": "function",
                    "function": {
                        "name": spec.name,
                        "description": spec.description,
                        "parameters": spec.input_schema,
                    },
                }
            )
        return out

    def list_resources(self) -> list[dict[str, Any]]:
        return [
            {
                "uri": "seed://policy/hardship-v1",
                "name": "PayPal Seed Hardship Policy v1 & Validator Rules V01-V12",
                "mimeType": "application/json",
            },
            {
                "uri": "seed://policy/tier-ladder",
                "name": "PayPal Seed 5-Tier Credit Record & Microloan Limit Ladder",
                "mimeType": "application/json",
            },
        ]

    def read_resource(self, uri: str) -> dict[str, Any]:
        if uri == "seed://policy/tier-ladder":
            return {"uri": uri, "mimeType": "application/json", "text": json.dumps({"tiers": TIER_LADDER})}
        if uri == "seed://policy/hardship-v1":
            policy = {
                "version": "hardship-v1",
                "zeroFees": True,
                "zeroInterest": True,
                "optionDBurdenBps": 1500,
                "validatorChecks": [
                    "V01_SCHEMA",
                    "V02_TOKEN_BINDING",
                    "V03_LEVEL_NOT_ESCALATE_ONLY",
                    "V04_OPTION_SET_OR_PREVIEW_MISMATCH",
                    "V05_RECOMMENDATION_NOT_FROM_CODE",
                    "V06_UNGROUNDED_FIGURE",
                    "V07_FORBIDDEN_CONTENT",
                    "V08_POLICY_INELIGIBLE",
                    "V09_OUTREACH_LIMIT",
                    "V10_LANGUAGE",
                    "V11_LENGTH",
                    "V12_CASE_STATE",
                ],
            }
            return {"uri": uri, "mimeType": "application/json", "text": json.dumps(policy)}
        raise KeyError(f"Unknown MCP resource URI: {uri}")

    def list_prompts(self) -> list[dict[str, Any]]:
        return [
            {
                "name": "guardian-investigate",
                "description": "Autonomous hardship investigation, option simulation, and Option D agentic checkout",
                "arguments": [{"name": "loanId", "required": True}, {"name": "caseId", "required": False}],
            },
            {
                "name": "fair-check-underwrite",
                "description": "Zero-credit-score microloan underwriting over consented PayPal cash flow",
                "arguments": [{"name": "requestedAmountCents", "required": True}, {"name": "termMonths", "required": True}],
            },
            {
                "name": "wealth-mobility-plan",
                "description": "Simulate savings streak and payday alignment to unlock the next credit tier",
                "arguments": [{"name": "currentTier", "required": True}, {"name": "streakMonths", "required": True}],
            },
            {
                "name": "desk-sla-triage",
                "description": "Triage loan officer queues, SLA breaches, and pre-verified maker-checker approvals",
                "arguments": [],
            },
        ]

    def call_tool(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
        ctx: AgentTokenContext | None = None,
        *,
        wrap_untrusted: bool = False,
    ) -> dict[str, Any]:
        if name not in self._specs:
            raise KeyError(f"Unknown MCP tool: '{name}'")
        spec = self._specs[name]
        token_ctx = ctx or AgentTokenContext()
        data = spec.handler(self.gateway, arguments or {}, token_ctx)
        if wrap_untrusted:
            return {"tool": name, "data": data, "fenced": wrap_untrusted_tool_output(name, data)}
        return data

    def handle_jsonrpc(self, request: dict[str, Any], ctx: AgentTokenContext | None = None) -> dict[str, Any] | None:
        """Handles a single JSON-RPC 2.0 MCP message (supports both stdio and HTTP POST /mcp)."""
        method = request.get("method", "")
        req_id = request.get("id")
        params = request.get("params") or {}

        # Notifications have no id and expect no response
        if req_id is None and method.startswith("notifications/"):
            return None

        try:
            if method == "initialize":
                result: Any = {
                    "protocolVersion": self.PROTOCOL_VERSION,
                    "serverInfo": {"name": self.SERVER_NAME, "version": self.SERVER_VERSION},
                    "capabilities": {
                        "tools": {"listChanged": False},
                        "resources": {"subscribe": False, "listChanged": False},
                        "prompts": {"listChanged": False},
                    },
                }
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": self.list_tools()}
            elif method == "tools/call":
                tool_name = params.get("name", "")
                tool_args = params.get("arguments") or {}
                output = self.call_tool(tool_name, tool_args, ctx=ctx)
                result = {
                    "content": [{"type": "text", "text": json.dumps(output, indent=2)}],
                    "structuredContent": output,
                    "isError": False,
                }
            elif method == "resources/list":
                result = {"resources": self.list_resources()}
            elif method == "resources/read":
                uri = params.get("uri", "")
                result = {"contents": [self.read_resource(uri)]}
            elif method == "prompts/list":
                result = {"prompts": self.list_prompts()}
            else:
                return {
                    "jsonrpc": "2.0",
                    "id": req_id,
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }

            return {"jsonrpc": "2.0", "id": req_id, "result": result}

        except (PermissionError, LookupError, ValueError, KeyError) as exc:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [{"type": "text", "text": json.dumps({"error": str(exc)})}],
                    "isError": True,
                },
            }
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32603, "message": str(exc)},
            }

    def serve_stdio(self) -> None:
        """Runs the MCP server over standard input/output (for Claude Desktop, Cursor, MCP Inspector)."""
        for line in sys.stdin:
            stripped = line.strip()
            if not stripped:
                continue
            try:
                req = json.loads(stripped)
            except json.JSONDecodeError:
                continue
            resp = self.handle_jsonrpc(req)
            if resp is not None:
                sys.stdout.write(json.dumps(resp) + "\n")
                sys.stdout.flush()


def main() -> None:
    server = SeedMcpServer()
    server.serve_stdio()


if __name__ == "__main__":
    main()
