# ADR 0003: Agent Read-Only Scopes and Proposal Intake

## Status
Accepted

## Context
AI agents (Astropods) provide coaching, underwriting assistance, and hardship detection, but must never directly alter financial or loan state.

## Decision
Agents are granted read-only tools and cannot access write APIs. Any suggested action is submitted as an untrusted proposal through `agent-gateway`. Proposals undergo maker-checker governance before execution by the target module.

## Consequences
- Prevents prompt injection attacks from directly modifying account balances or approving loans.
- Creates full auditability of AI decisions.
