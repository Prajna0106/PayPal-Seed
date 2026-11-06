"""Trajectory & Step-by-Step Reasoning Trace Store for PayPal Seed Agents."""

from __future__ import annotations

import enum
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any


class TraceStepType(str, enum.Enum):
    PLAN = "PLAN"
    THOUGHT = "THOUGHT"
    TOOL_CALL = "TOOL_CALL"
    OBSERVATION = "OBSERVATION"
    SIMULATION = "SIMULATION"
    GUARDRAIL_CHECK = "GUARDRAIL_CHECK"
    ACTION = "ACTION"


@dataclass
class TraceStep:
    step_index: int
    step_type: str  # "PLAN" | "THOUGHT" | "TOOL_CALL" | "OBSERVATION" | "SIMULATION" | "GUARDRAIL_CHECK" | "ACTION"
    agent: str
    summary: str
    tool_name: str | None = None
    tool_args: dict[str, Any] | None = None
    tool_output: dict[str, Any] | None = None
    duration_ms: float = 0.0
    timestamp: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "stepIndex": self.step_index,
            "stepType": self.step_type,
            "agent": self.agent,
            "summary": self.summary,
            "durationMs": round(self.duration_ms, 2),
            "timestamp": self.timestamp,
        }
        if self.tool_name:
            out["toolName"] = self.tool_name
        if self.tool_args is not None:
            out["toolArgs"] = self.tool_args
        if self.tool_output is not None:
            out["toolOutput"] = self.tool_output
        return out


@dataclass
class AgentRunTrace:
    run_id: str
    agent: str
    goal: str
    status: str = "RUNNING"  # "RUNNING" | "PASSED" | "FELL_BACK" | "REJECTED"
    model_used: str = "deterministic-react-planner"
    started_at: float = field(default_factory=time.perf_counter)
    steps: list[TraceStep] = field(default_factory=list)
    verdict: dict[str, Any] | None = None
    human_in_the_loop: dict[str, Any] | None = None
    final_output: dict[str, Any] | None = None

    def add_step(
        self,
        step_type: str,
        summary: str,
        *,
        tool_name: str | None = None,
        tool_args: dict[str, Any] | None = None,
        tool_output: dict[str, Any] | None = None,
        duration_ms: float = 0.0,
    ) -> TraceStep:
        step = TraceStep(
            step_index=len(self.steps) + 1,
            step_type=step_type,
            agent=self.agent,
            summary=summary,
            tool_name=tool_name,
            tool_args=tool_args,
            tool_output=tool_output,
            duration_ms=duration_ms,
        )
        self.steps.append(step)
        return step

    @property
    def tool_calls_count(self) -> int:
        return sum(1 for s in self.steps if s.step_type in ("TOOL_CALL", "SIMULATION"))

    @property
    def total_duration_ms(self) -> float:
        return round((time.perf_counter() - self.started_at) * 1000.0, 2)

    def to_dict(self) -> dict[str, Any]:
        return {
            "runId": self.run_id,
            "agent": self.agent,
            "goal": self.goal,
            "status": self.status,
            "modelUsed": self.model_used,
            "toolCallsCount": self.tool_calls_count,
            "totalSteps": len(self.steps),
            "totalDurationMs": self.total_duration_ms,
            "verdict": self.verdict,
            "humanInTheLoop": self.human_in_the_loop,
            "steps": [s.to_dict() for s in self.steps],
            "output": self.final_output,
        }


class TraceStore:
    """In-memory bounded store of recent agent execution traces for UI & eval inspection."""

    def __init__(self, max_traces: int = 200) -> None:
        self.max_traces = max_traces
        self._traces: dict[str, AgentRunTrace] = {}
        self._order: list[str] = []

    def save(self, trace: AgentRunTrace) -> None:
        if trace.run_id not in self._traces:
            self._order.append(trace.run_id)
            if len(self._order) > self.max_traces:
                oldest = self._order.pop(0)
                self._traces.pop(oldest, None)
        self._traces[trace.run_id] = trace

    def get(self, run_id: str) -> AgentRunTrace | None:
        return self._traces.get(run_id)

    def list_recent(self, limit: int = 25) -> list[dict[str, Any]]:
        ids = list(reversed(self._order[-limit:]))
        return [self._traces[rid].to_dict() for rid in ids if rid in self._traces]
