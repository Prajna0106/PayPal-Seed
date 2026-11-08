# Verify a credit record

A Seed member can share their credit record with a lender. They choose how much to show. You check the link and read the answer. You never see their name, email, loan amounts or member id.

## The flow

1. The member opens **Record** in the Seed app (`/app/record`) and creates a share link.
2. The member sends you the link. It looks like `https://<seed-app>/app/record/<token>`.
3. You take the last path segment. That is the token. Pass it as is.
4. You call `verifyRecord` with the token.
5. You read the answer and make your own decision.

## Call it

```bash
curl https://seed-api-4hui.onrender.com/api/v1/records/verify/v1.k1.AAAA.BBBB
```

```typescript
const token = new URL(shareUrl).pathname.split("/").filter(Boolean).pop()!;
const res = await fetch(
  `https://seed-api-4hui.onrender.com/api/v1/records/verify/${encodeURIComponent(token)}`,
);
if (res.status === 404) throw new Error("This link is not valid.");
const record = await res.json();
```

```python
from urllib.parse import urlparse
import requests

token = [p for p in urlparse(share_url).path.split("/") if p][-1]
r = requests.get(
    f"https://seed-api-4hui.onrender.com/api/v1/records/verify/{token}", timeout=30
)
if r.status_code == 404:
    raise SystemExit("This link is not valid.")
record = r.json()
```

## Read the answer

| Field | Meaning |
| --- | --- |
| `valid` | `true` if the record still stands. `false` if the member has had a default since the link was made. Treat `false` as "do not rely on this". |
| `level` | `NEW`, `BUILDING`, `STEADY`, `TRUSTED` or `EXEMPLARY`, lowest to highest. Always present. |
| `since` | First day of the month the record began. Only if the member shared a summary or more. |
| `loansRepaidOnPlan` | Loans repaid on the agreed plan. Only with a summary or more. |
| `loansCompletedWithFundHelp` | Loans the community safety fund paid in full. A count only. It does not say why help was needed. It is not part of `loansRepaidOnPlan`. |
| `onTimeBand` | On-time share as a band: `below 70%`, `70-84%`, `85-94%`, `95-100%`. |
| `issuedAt`, `expiresAt` | When the link was made and when it stops working. |

If the member shared only the level, the optional fields are missing. Do not treat a missing field as zero.

## When the answer is 404

An unknown, expired, revoked or altered token all return the same `404` with code `record-not-found`. Nothing says which one it was. Tell the applicant the link did not work and ask for a new one.

## Good practice

- Responses may be cached for 5 seconds. Do not poll faster than that.
- Do not store the member's record. Verify again when you need it.
- Do not log the token. It is a credential until it expires.
- The member can say no. Use the record as one input, with your own checks.
