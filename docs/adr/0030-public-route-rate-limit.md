# ADR 0030: Rate Limit on the Public Routes

## Status
Proposed. Builds on [ADR 0014](0014-credit-record-share-links.md) (the public verify route) and [ADR 0019](0019-the-reserve-and-fraud-flags.md) (the public reserve summary and donation intent).

## Context
Three routes need no sign-in: `GET /api/v1/records/verify/{token}`, `GET /api/v1/reserve/summary` and `POST /api/v1/reserve/donations/intent`. The contract asked for an edge rate limit in front of them, but Render has no limiter, so nothing stood between the internet and a database read or write per request.

## Decision

### 1. A servlet filter in `platform/security`
`PublicRateLimitFilter` runs before the dispatcher and counts requests per client address in fixed one-minute windows (`FixedWindowRateLimiter`), one allowance shared by the three routes. Past the allowance it answers `429` as an RFC 9457 problem with `type` `https://problems.paypal-seed.dev/rate-limited`, `code` `rate-limited` and `Retry-After` in seconds. A filter sits outside `ApiErrorHandler`, so it writes the same problem shape itself; no stack trace or internal detail is in it. Other routes are not touched.

### 2. Who the client is
`seed.security.public-rate-limit.trusted-proxy-hops` (default 1) says how many proxies in front of the app append to `X-Forwarded-For`. Only the Nth entry from the right is trusted, so a caller who sends their own `X-Forwarded-For` cannot pick the address that is counted. If the header has fewer entries than hops, or is missing, the socket address is used. `0` ignores the header.

### 3. Settings
`seed.security.public-rate-limit.*`: `enabled` (default true), `requests-per-minute` (default 60), `trusted-proxy-hops` (default 1), `max-tracked-clients` (default 10000). The test profile switches it off because many integration tests call these routes from one address; `PublicRateLimitIT` switches it on.

### 4. Bounded memory
The counter map holds at most `max-tracked-clients` live windows. At the bound, expired windows are dropped; if it is still full a new address is refused with `429` instead of the map growing. A flood of distinct addresses can therefore deny new addresses for up to a minute, never exhaust the heap. Known addresses already counted keep their own allowance.

## Consequences
- The limit is per instance and in memory. With N api instances a client may make up to N times the allowance, and a restart clears the counters. That is acceptable for a guard against scraping and token guessing; it is not a precise quota.
- It does not stop volumetric attacks that reach the app at all. A rule at a CDN or load balancer in front of Render is still advisable and should be added when one is available; this filter is the backstop behind it.
- The contract documents `429` and `Retry-After` on the three routes. The web side shows the plain "try again shortly" message for the `rate-limited` type.
