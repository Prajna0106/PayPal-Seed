# PayPal Seed

> Community-led micro-lending and savings platform built with PayPal integration, community safety funds, deterministic rules engines, and autonomous agent governance.

## Architecture Highlights

- **Modular Monolith**: Java 21, Spring Boot 3, and Spring Modulith with strict architectural boundary enforcement (ArchUnit).
- **Frontends**: Next.js App Router applications (`apps/member` and `apps/desk`) with Turborepo and pnpm workspaces.
- **Pure Core Engines**: Deterministic, zero-IO computation engines (`schedule`, `rules`, `assessment`) tested with property-based testing (jqwik).
- **Hard Module Boundaries**: Postgres schema-per-module, transactional outbox pattern, and strict in-process API facades.
- **Single Synchronous Ledger**: Money state changes, journal entries, and outbox publications commit in a single ACID transaction.
- **Runtime Roles**: The same single deployable backend artifact (`seed-app`) runs in three distinct operational roles: `api`, `ingress`, and `worker`.
- **Autonomous Agent Proposals**: Agents (Astropods) have read-only tools and submit proposals through the governance module; they cannot directly invoke write operations.

## Directory Structure

Refer to [docs/STRUCTURE.md](docs/STRUCTURE.md) for full layout specifications, architectural decision records, and dependency graphs.
