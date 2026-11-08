# Errors and limits

## Error shape

Every error is an RFC 9457 problem with content type `application/problem+json`.

```json
{
  "type": "https://problems.paypal-seed.dev/record-not-found",
  "title": "No such record",
  "status": 404,
  "detail": "No such record",
  "instance": "/api/v1/records/verify",
  "code": "record-not-found"
}
```

Branch on `code`. Titles and details are for people and may change. No stack traces are ever returned.

## Codes on these routes

| Status | `code` | Route | What to do |
| --- | --- | --- | --- |
| 400 | `validation-failed` | donation intent | Fix the body or add the `Idempotency-Key` header. |
| 404 | `record-not-found` | verify | The link is unknown, expired, revoked or altered. Ask for a new link. |
| 409 | `idempotency-key-reused` | donation intent | Same key, different body. Use a new key for a new action. |
| 429 | `rate-limited` | all three | Wait `Retry-After` seconds, then retry. |
| 503 | `dependency-unavailable` | verify | Seed cannot verify right now. Retry later. |

## Rate limit

Each client address gets a limited number of requests per minute, shared by the three routes. Over the limit you get `429` with a `Retry-After` header in seconds. Cache answers for the allowed time and do not poll faster.

### Retry with backoff

```typescript
async function getWithRetry(url: string, tries = 3): Promise<Response> {
  for (let attempt = 1; ; attempt++) {
    const res = await fetch(url);
    if (res.status !== 429 && res.status !== 503) return res;
    if (attempt >= tries) return res;
    const wait = Number(res.headers.get("Retry-After")) || 2 ** attempt;
    await new Promise((r) => setTimeout(r, wait * 1000));
  }
}
```

```python
import time, requests

def get_with_retry(url, tries=3):
    for attempt in range(1, tries + 1):
        r = requests.get(url, timeout=30)
        if r.status_code not in (429, 503) or attempt == tries:
            return r
        time.sleep(int(r.headers.get("Retry-After", 2 ** attempt)))
```

```bash
curl -i https://seed-api-4hui.onrender.com/api/v1/reserve/summary   # look for Retry-After on a 429
```

## Idempotency

`POST /api/v1/reserve/donations/intent` needs an `Idempotency-Key` header, up to 128 characters. Create one per user action, for example a UUID. Retrying a failed or timed-out call with the same key and body is safe: you get the same result. Reusing a key with a different body returns `409`.

`GET` calls are safe to repeat.
