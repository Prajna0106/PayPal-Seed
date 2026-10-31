# ADR 0008: Type-Safe SQL with jOOQ, Generated Per Module From the Migrations

## Status
Accepted (supersedes the "plain JdbcClient" choice made while building F4)

## Context
The data is mostly append-only facts and money. The code relies on SQL features an ORM hides: `SKIP LOCKED` queues, `ON CONFLICT` idempotency, deferred constraint triggers, partial unique indexes and row locks. Plain `JdbcClient` kept all of that visible, but SQL lived in strings: a renamed column failed at runtime, and every row mapper was written by hand.

## Decision
- **jOOQ, not an ORM.** Queries are still written in SQL form, now checked by the compiler against the real schema.
- **Generated per module, from that module's own Flyway migrations.** A convention plugin (`build-logic/.../jooq-module.gradle.kts`) adds a `generateJooq` task to every module that owns a schema. It starts a throwaway PostgreSQL 16 (the same embedded server the tests use, so no Docker), runs the module's migrations, and runs the jOOQ generator. Output goes to `build/generated-src/jooq/main` in package `com.paypalseed.<module>.infrastructure.persistence.jooq`.
- **No drift, no committed generated code.** The classes are rebuilt whenever a migration changes; Gradle skips the task otherwise and can cache it.
- **Module isolation survives.** A module generates only its own schema, so another module's tables are not on its classpath. ArchUnit also confines `org.jooq` to `infrastructure.persistence` packages.
- **Spring owns the transaction.** Spring Boot builds the `DSLContext` on the Spring-managed DataSource, so jOOQ statements join the surrounding transaction, including `MANDATORY`, `REQUIRES_NEW` and savepoints.

## Scale decisions made with it
- **Keyset pagination in close of business.** Pages are `id > :after ORDER BY id LIMIT n`. The earlier version passed an ever-growing "already seen" array to every query.
- **Shard filter the index can use.** `shard IN (3, 11, 19, ...)` instead of `shard % 8 = 3`, which could not use the `(shard, id)` index.
- **Multi-row inserts** for instalments, allocations and timers: one statement per table instead of one per row.
- **Guard rails.** A statement timeout (`seed.db.query-timeout-seconds`, default 30), a slow-query log (`seed.db.slow-query-ms`, default 250, SQL without bound values), a fail-fast pool wait (`DATABASE_CONNECTION_TIMEOUT_MS`), and no jOOQ execute logging (it prints bound values).

## Consequences
- Column renames and type changes break the build, not production.
- The first build of a module starts a PostgreSQL process (a few seconds); later builds reuse the cached output.
- The `platform.business_date` demo-clock query still uses `JdbcClient`: it is one query on one table owned by the platform, so a generated schema for it would cost more than it returns.
- Dynamic desk filtering (the AG Grid server-side row model) can build jOOQ `Condition`s from the filter model instead of concatenating SQL.
