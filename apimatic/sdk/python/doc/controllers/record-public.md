# Record-Public

Verify a credit record a member chose to share. No sign-in; the token is the credential.

```python
record_public_controller = client.record_public
```

## Class Name

`RecordPublicController`


# Verify Record

Returns only what the member chose to share: level, since date, loans repaid on plan, loans completed with
safety-fund help, an on-time band, when the link was issued and when it expires, and a `valid` flag.
`valid` is false when the member has had a default since the snapshot was taken, so a partner does not rely
on an out-of-date answer.

Unknown, expired, revoked and tampered tokens all return the same generic 404 `record-not-found`; nothing
says which. The response never contains a name, an email, a payer id, a loan amount or a member id. It may
be cached for 5 seconds. The app limits each client address (429 `rate-limited`, ADR 0030).

```python
def verify_record(self,
                 token)
```

## Parameters

| Parameter | Type | Tags | Description |
|  --- | --- | --- | --- |
| `token` | `str` | Template, Required | The token from the member's share link, the last path segment of `/app/record/...`. Pass it as is.<br><br>**Constraints**: *Maximum Length*: `400` |

## Response Type

**200**: Verified

[`VerifiedRecord`](../../doc/models/verified-record.md)

## Example Usage

```python
token = 'v1.k1.AAAA.BBBB'

result = record_public_controller.verify_record(token)
print(result)
```

## Example Response *(as JSON)*

```json
{
  "valid": true,
  "level": "STEADY",
  "since": "2026-01-01",
  "loansRepaidOnPlan": 2,
  "loansCompletedWithFundHelp": 0,
  "onTimeBand": "95-100%",
  "issuedAt": "2026-11-08T17:00:00Z",
  "expiresAt": "2026-11-15T17:00:00Z"
}
```

## Errors

| HTTP Status Code | Error Description | Exception Class |
|  --- | --- | --- |
| 404 | record-not-found. Unknown, expired, revoked or tampered token; the answer is the same for all four. | [`ProblemException`](../../doc/models/problem-exception.md) |
| 429 | rate-limited. Too many requests from this address. Wait `Retry-After` seconds, then retry. | [`ProblemException`](../../doc/models/problem-exception.md) |
| 503 | dependency-unavailable (no signing key is configured). Retry later. | [`ProblemException`](../../doc/models/problem-exception.md) |

