# Conventions

## Money

Money is an integer number of cents in US dollars. `125000` means $1,250.00. Field names end in `Cents`. Never use floating point for money.

```typescript
const dollars = (cents: number) => (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });
```

## Dates and times

- Business dates (`since`, `asOf`) are plain `YYYY-MM-DD` in the America/Los_Angeles time zone. Do not convert them with a time zone. Show them as written.
- Timestamps (`issuedAt`, `expiresAt`) are UTC in RFC 3339, for example `2026-11-08T17:00:00Z`.

## Ratios

`coverageRatioBps` is in basis points. `10000` is 100%. It can be `null`.

## Optional fields

A field the member did not share is left out. A missing field is not zero.

## No personal data

Responses never contain a name, email, payer id, loan amount or member id. Public display names in `supporters` are only from donors who opted in.

## Headers

| Header | Where | Meaning |
| --- | --- | --- |
| `Idempotency-Key` | request, donation intent | Makes retries safe. |
| `Retry-After` | response, 429 | Seconds to wait. |
| `Cache-Control` | response, 200 | `max-age=5` on verify, `max-age=30` on summary. |

## Content types

Success bodies are `application/json`. Errors are `application/problem+json`.
