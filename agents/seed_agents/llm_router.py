"""OpenRouter Multi-Model Failover Client with Tool-Calling & Zero-Data-Retention Routing."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any

import httpx

from .config import AgentRuntimeConfig, load_config
from .guardrails import redact_pii


@dataclass(frozen=True)
class LlmCompletion:
    model: str
    content: str
    tool_calls: list[dict[str, Any]]
    prompt_tokens: int = 0
    completion_tokens: int = 0
    live_model: bool = False


class OpenRouterFailoverClient:
    """Calls OpenRouter across the configured primary-to-fallback model chain (ADR 0010)."""

    def __init__(self, config: AgentRuntimeConfig | None = None) -> None:
        self.config = config or load_config()

    @property
    def is_live_configured(self) -> bool:
        return bool(self.config.openrouter_api_key and self.config.openrouter_api_key.strip())

    def complete(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        response_schema: dict[str, Any] | None = None,
        max_tokens: int = 900,
    ) -> LlmCompletion | None:
        if not self.is_live_configured:
            return None

        # Redact PII on all outgoing text messages
        safe_messages = []
        for msg in messages:
            copied = dict(msg)
            if isinstance(copied.get("content"), str):
                copied["content"] = redact_pii(copied["content"])
            safe_messages.append(copied)

        for model_name in self.config.openrouter_models:
            try:
                payload: dict[str, Any] = {
                    "model": model_name,
                    "messages": safe_messages,
                    "max_tokens": max_tokens,
                    "temperature": 0.1,
                    "provider": {"data_collection": "deny"},
                }
                if tools:
                    payload["tools"] = tools
                    payload["tool_choice"] = "auto"
                if response_schema:
                    payload["response_format"] = {"type": "json_object"}

                with httpx.Client(
                    base_url=self.config.openrouter_base_url,
                    timeout=self.config.per_call_timeout_seconds,
                ) as client:
                    resp = client.post(
                        "/chat/completions",
                        headers={
                            "Authorization": f"Bearer {self.config.openrouter_api_key}",
                            "Content-Type": "application/json",
                            "HTTP-Referer": "https://paypal-seed.dev",
                            "X-Title": "PayPal Seed Autonomous Agents",
                        },
                        json=payload,
                    )
                    if resp.status_code != 200:
                        continue
                    data = resp.json()
                    choice = (data.get("choices") or [{}])[0]
                    msg = choice.get("message") or {}
                    usage = data.get("usage") or {}
                    return LlmCompletion(
                        model=data.get("model", model_name),
                        content=msg.get("content") or "",
                        tool_calls=msg.get("tool_calls") or [],
                        prompt_tokens=int(usage.get("prompt_tokens", 0)),
                        completion_tokens=int(usage.get("completion_tokens", 0)),
                        live_model=True,
                    )
            except Exception:
                continue

        return None

    @staticmethod
    def try_parse_json(raw: str) -> dict[str, Any] | None:
        if not raw:
            return None
        cleaned = raw.strip()
        if cleaned.startswith("```"):
            cleaned = cleaned.strip("`")
            if cleaned.startswith("json"):
                cleaned = cleaned[4:].strip()
        try:
            parsed = json.loads(cleaned)
            return parsed if isinstance(parsed, dict) else None
        except Exception:
            return None
