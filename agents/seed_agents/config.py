"""Environment configuration for the PayPal Seed Agent & MCP Runtime."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class AgentRuntimeConfig:
    """Configuration resolved strictly from environment variables (never hardcoded secrets)."""

    seed_api_url: str = field(default_factory=lambda: os.getenv("SEED_API_URL", "http://localhost:8080"))
    agent_port: int = field(default_factory=lambda: int(os.getenv("SEED_AGENT_PORT", "8090")))

    # OpenRouter multi-model failover chain (ADR 0010)
    openrouter_api_key: str = field(default_factory=lambda: os.getenv("OPENROUTER_API_KEY", ""))
    openrouter_base_url: str = field(
        default_factory=lambda: os.getenv("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1")
    )
    openrouter_models: tuple[str, ...] = field(
        default_factory=lambda: tuple(
            m.strip()
            for m in os.getenv(
                "SEED_LLM_MODELS",
                "google/gemini-2.0-flash-001,meta-llama/llama-3.3-70b-instruct,mistralai/mistral-small-24b-instruct-2501",
            ).split(",")
            if m.strip()
        )
    )

    # PayPal Sandbox configuration for Agentic Checkout (Option D)
    paypal_client_id: str = field(default_factory=lambda: os.getenv("PAYPAL_CLIENT_ID", ""))
    paypal_client_secret: str = field(default_factory=lambda: os.getenv("PAYPAL_CLIENT_SECRET", ""))
    paypal_base_url: str = field(
        default_factory=lambda: os.getenv("PAYPAL_BASE_URL", "https://api-m.sandbox.paypal.com")
    )
    member_app_origin: str = field(
        default_factory=lambda: os.getenv("APP_ORIGIN", "http://localhost:3000")
    )

    # Execution budgets (F5 §3)
    max_tool_calls_per_run: int = 6
    max_run_seconds: float = 45.0
    per_call_timeout_seconds: float = 20.0


def load_config() -> AgentRuntimeConfig:
    return AgentRuntimeConfig()
