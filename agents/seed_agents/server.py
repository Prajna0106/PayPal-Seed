"""HTTP Server for PayPal Seed Autonomous Agent Runtime & MCP Server (`:8090`).

Serves:
  - GET  /health                  -> Runtime status, registered agents & MCP tool count
  - POST /mcp                     -> Model Context Protocol (MCP) JSON-RPC 2.0 endpoint
  - GET  /v1/agents/tools         -> Full MCP tool catalogue & scopes
  - POST /v1/agents/run           -> Supervisor Orchestrator & 4 Autonomous ReAct Agents
  - GET  /v1/agents/traces        -> Recent multi-step reasoning & tool-call trajectories
  - GET  /v1/agents/traces/{id}   -> Specific run trajectory by runId
"""

from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from .config import AgentRuntimeConfig, load_config
from .react_engine import SeedSupervisorOrchestrator


class AgentRuntimeDispatcher:
    """Transport-agnostic HTTP dispatcher for the Agent Runtime & MCP Server."""

    def __init__(self, config: AgentRuntimeConfig | None = None) -> None:
        self.config = config or load_config()
        self.orchestrator = SeedSupervisorOrchestrator(self.config)

    def handle_get(self, path: str) -> tuple[int, dict[str, Any]]:
        parsed = urlparse(path)
        route = parsed.path.rstrip("/") or "/"
        query_params = parse_qs(parsed.query)

        if route in ("/", "/health"):
            tools = self.orchestrator.mcp.list_tools()
            agents_list = [
                "guardian-agent",
                "underwriting-agent",
                "wealth-mobility-agent",
                "desk-analyst-agent",
            ]
            return 200, {
                "service": "paypal-seed-agent-runtime",
                "status": "UP",
                "mcpServer": self.orchestrator.mcp.SERVER_NAME,
                "mcpProtocolVersion": self.orchestrator.mcp.PROTOCOL_VERSION,
                "mcp_protocol": self.orchestrator.mcp.PROTOCOL_VERSION,
                "toolsCount": len(tools),
                "active_agents": agents_list,
                "agents": agents_list,
                "openRouterModels": list(self.config.openrouter_models),
                "recentTracesCount": len(self.orchestrator.traces.list_recent(limit=200)),
            }

        if route == "/v1/agents/tools":
            tools = self.orchestrator.mcp.list_tools()
            return 200, {"count": len(tools), "tools": tools}

        if route == "/v1/agents/traces":
            run_id = query_params.get("runId", [None])[0]
            if run_id:
                trace = self.orchestrator.traces.get(run_id)
                if not trace:
                    return 404, {"type": "https://problems.paypal-seed.dev/trace-not-found", "status": 404}
                td = trace.to_dict()
                td["step_count"] = len(td.get("steps", []))
                return 200, td
            return 200, {"traces": self.orchestrator.traces.list_recent(limit=25)}

        if route.startswith("/v1/agents/traces/"):
            run_id = route.split("/v1/agents/traces/", 1)[1]
            trace = self.orchestrator.traces.get(run_id)
            if not trace:
                return 404, {"type": "https://problems.paypal-seed.dev/trace-not-found", "status": 404}
            td = trace.to_dict()
            td["step_count"] = len(td.get("steps", []))
            return 200, td

        return 404, {"type": "https://problems.paypal-seed.dev/not-found", "status": 404}

    def handle_post(self, path: str, body: dict[str, Any]) -> tuple[int, dict[str, Any]]:
        parsed = urlparse(path)
        route = parsed.path.rstrip("/")

        if route == "/mcp":
            resp = self.orchestrator.mcp.handle_jsonrpc(body)
            return 200, resp or {"jsonrpc": "2.0", "status": "accepted"}

        if route == "/v1/agents/run":
            # Normalize request payload for versatile callers
            goal = body.get("goal") or body.get("userMessage") or body.get("prompt") or "Run agent goal"
            params = body.get("params") or {}
            if "memberText" not in params and "userMessage" in body:
                params["memberText"] = body["userMessage"]
            if "loanId" in body and "loanId" not in params:
                params["loanId"] = body["loanId"]
            if "caseId" in body and "caseId" not in params:
                params["caseId"] = body["caseId"]

            norm_req = {
                "goal": goal,
                "agent": body.get("agent"),
                "params": params,
            }
            trace_dict = self.orchestrator.run_goal(norm_req)

            # Flatten convenience fields if final_output is present
            final_out = trace_dict.get("final_output") or {}
            if isinstance(final_out, dict):
                for k, v in final_out.items():
                    if k not in trace_dict:
                        trace_dict[k] = v

            return 200, trace_dict

        return 404, {"type": "https://problems.paypal-seed.dev/not-found", "status": 404}


def make_http_handler(dispatcher: AgentRuntimeDispatcher) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self) -> None:  # noqa: N802
            status, payload = dispatcher.handle_get(self.path)
            self._send_json(status, payload)

        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
            try:
                body = json.loads(raw_body) if raw_body.strip() else {}
            except json.JSONDecodeError:
                self._send_json(400, {"error": "Invalid JSON body"})
                return
            status, payload = dispatcher.handle_post(self.path, body)
            self._send_json(status, payload)

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return

    return Handler


_default_dispatcher = AgentRuntimeDispatcher()
SeedAgentHttpHandler = make_http_handler(_default_dispatcher)


def main() -> None:
    config = load_config()
    dispatcher = AgentRuntimeDispatcher(config)
    server = ThreadingHTTPServer(("0.0.0.0", config.agent_port), make_http_handler(dispatcher))
    print(f"[seed-agents] MCP & Autonomous Agent Runtime listening on http://0.0.0.0:{config.agent_port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()


if __name__ == "__main__":
    main()
