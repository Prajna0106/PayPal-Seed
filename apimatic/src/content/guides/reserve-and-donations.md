# Safety fund and donations

The community safety fund helps members who hit a hard month. Its totals are public.

## Read the summary

```bash
curl https://seed-api-4hui.onrender.com/api/v1/reserve/summary
```

Example response:

```json
{
  "balanceCents": 125000,
  "totalDonatedCents": 175000,
  "donationCount": 31,
  "totalPaidOutCents": 50000,
  "membersCovered": 3,
  "openExposureCents": 900000,
  "coverageRatioBps": 1388,
  "supporters": ["Maria", "Tom"],
  "asOf": "2026-11-10"
}
```

- All money is integer cents in USD. `125000` is $1,250.00.
- `coverageRatioBps` is balance divided by open exposure, in basis points. `10000` means the fund could repay every open loan once. It is `null` when there are no open loans.
- `supporters` lists display names of recent donors who chose to be shown. Never emails or amounts.
- The response may be cached for 30 seconds.

## Start a donation

`createDonationIntent` returns a `customId`. Put it on your PayPal order or Donate button as `custom_id`. When PayPal captures the payment, Seed matches it to the donor's choice. No PayPal credential is returned.

The `Idempotency-Key` header is required. Use a new UUID for each user action. Retrying with the same key and body returns the same `customId`. The same key with a different body returns `409`.

```bash
curl -X POST https://seed-api-4hui.onrender.com/api/v1/reserve/donations/intent \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"anonymous": false, "displayName": "Maria"}'
```

```typescript
const res = await fetch("https://seed-api-4hui.onrender.com/api/v1/reserve/donations/intent", {
  method: "POST",
  headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
  body: JSON.stringify({ anonymous: false, displayName: "Maria" }),
});
const { intentId, customId } = await res.json();
```

```python
import uuid, requests

r = requests.post(
    "https://seed-api-4hui.onrender.com/api/v1/reserve/donations/intent",
    headers={"Idempotency-Key": str(uuid.uuid4())},
    json={"anonymous": False, "displayName": "Maria"},
    timeout=30,
)
custom_id = r.json()["customId"]
```

Donors are anonymous by default. `displayName` is at most 40 characters and shows only when `anonymous` is `false`.
