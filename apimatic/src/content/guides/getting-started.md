# Getting started

The Seed Partner API lets another lender check a member's Seed credit record, and read the public safety-fund totals. No account or API key is needed. The member's share link is the credential.

## Base URL

| Server | URL |
| --- | --- |
| Sandbox (demo data, no real money) | `https://seed-api-4hui.onrender.com` |
| Local | `http://localhost:8080` |

The sandbox runs on a free host. After idle time, the first call can take up to a minute.

## Your first call

Read the public safety-fund summary. It needs no token.

```bash
curl https://seed-api-4hui.onrender.com/api/v1/reserve/summary
```

```typescript
const res = await fetch("https://seed-api-4hui.onrender.com/api/v1/reserve/summary");
const summary = await res.json();
console.log(summary.balanceCents);
```

```python
import requests

r = requests.get("https://seed-api-4hui.onrender.com/api/v1/reserve/summary", timeout=30)
print(r.json()["balanceCents"])
```

## What you can do

| Goal | Operation |
| --- | --- |
| Check a member's shared credit record | `verifyRecord` |
| Read the safety-fund totals | `getReserveSummary` |
| Start a donation | `createDonationIntent` |

Next: Verify a credit record, then Errors and limits.
