# Reserve

The community safety fund. Public totals and donation intents. No sign-in.

```python
reserve_controller = client.reserve
```

## Class Name

`ReserveController`

## Methods

* [Get Reserve Summary](../../doc/controllers/reserve.md#get-reserve-summary)
* [Create Donation Intent](../../doc/controllers/reserve.md#create-donation-intent)


# Get Reserve Summary

Exposure is the sum of outstanding balances of loans, kept up to date from lending events. The coverage
ratio is `balanceCents / openExposureCents` in basis points (10000 means the fund could repay every open
loan once); it is null when there is no exposure. The response may be cached for 30 seconds.

```python
def get_reserve_summary(self)
```

## Response Type

**200**: Summary

[`ReserveSummary`](../../doc/models/reserve-summary.md)

## Example Usage

```python
result = reserve_controller.get_reserve_summary()
print(result)
```

## Example Response *(as JSON)*

```json
{
  "balanceCents": 125000,
  "totalDonatedCents": 175000,
  "donationCount": 31,
  "totalPaidOutCents": 50000,
  "membersCovered": 3,
  "openExposureCents": 900000,
  "coverageRatioBps": 1388,
  "supporters": [
    "Maria",
    "Tom"
  ],
  "asOf": "2026-11-10"
}
```

## Errors

| HTTP Status Code | Error Description | Exception Class |
|  --- | --- | --- |
| 429 | rate-limited. Too many requests from this address. Wait `Retry-After` seconds, then retry. | [`ProblemException`](../../doc/models/problem-exception.md) |


# Create Donation Intent

Public. The server remembers whether the donor wants their display name shown (default: anonymous) and
returns a `customId` of the form `reserve-donation:{uuid}`. The web page passes it to PayPal as
`custom_id`; the capture webhook carries it back. No PayPal credential is returned. The same
`Idempotency-Key` with the same body returns the same `customId`. The same key with a different body
returns 409 `idempotency-key-reused`.

```python
def create_donation_intent(self,
                          idempotency_key,
                          body)
```

## Parameters

| Parameter | Type | Tags | Description |
|  --- | --- | --- | --- |
| `idempotency_key` | `str` | Header, Required | A unique value for this user action, for example a UUID. Retrying with the same key and body is safe and returns the same result.<br><br>**Constraints**: *Maximum Length*: `128` |
| `body` | [`DonationIntentRequest`](../../doc/models/donation-intent-request.md) | Body, Required | - |

## Response Type

**201**: Intent

[`DonationIntent`](../../doc/models/donation-intent.md)

## Example Usage

```python
idempotency_key = '6f1c2b7e-3d4a-4e5f-9a10-b2c3d4e5f607'

body = DonationIntentRequest(
    anonymous=False,
    display_name='Maria'
)

result = reserve_controller.create_donation_intent(
    idempotency_key,
    body
)
print(result)
```

## Example Response *(as JSON)*

```json
{
  "intentId": "0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10",
  "customId": "reserve-donation:0197f3a4-1b2c-7d3e-8f40-5a6b7c8d9e10"
}
```

## Errors

| HTTP Status Code | Error Description | Exception Class |
|  --- | --- | --- |
| 400 | validation-failed. The body is not valid, or the Idempotency-Key header is missing. | [`ProblemException`](../../doc/models/problem-exception.md) |
| 409 | idempotency-key-reused. The key was already used with a different body. | [`ProblemException`](../../doc/models/problem-exception.md) |
| 429 | rate-limited. Too many requests from this address. Wait `Retry-After` seconds, then retry. | [`ProblemException`](../../doc/models/problem-exception.md) |

