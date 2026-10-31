# ADR 0002: Ledger Single Transaction Boundary

## Status
Accepted

## Context
Financial movements require absolute consistency between business state and double-entry journal records.

## Decision
A money state change, its journal posting, and the resulting domain outbox event must commit within a single database transaction. Business modules invoke `ledger.api.post(...)` in-process synchronously.

## Consequences
- Impossible to create money movements without corresponding balanced ledger entries.
- High consistency guarantee for audit and reconciliation.
