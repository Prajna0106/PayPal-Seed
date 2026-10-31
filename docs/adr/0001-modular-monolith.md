# ADR 0001: Modular Monolith Architecture

## Status
Accepted

## Context
PayPal Seed needs strong domain boundaries, clear module ownership, and isolated schemas without the operational overhead of microservices at day one.

## Decision
Adopt a modular monolith using Java 21, Spring Boot 3, and Spring Modulith. Each bounded context has its own Gradle subproject and separate Postgres schema. Communication across modules is strictly asynchronous via domain events recorded in a transactional outbox, with the single exception of ledger posting.

## Consequences
- Fast developer loop and unified build.
- Architecture rules strictly enforced via ArchUnit.
- Clean extraction path for high-throughput modules.
