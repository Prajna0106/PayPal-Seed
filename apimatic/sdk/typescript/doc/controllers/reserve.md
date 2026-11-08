# Reserve

The community safety fund. Public totals and donation intents. No sign-in.

```ts
const reserveController = new ReserveController(client);
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

```ts
async getReserveSummary(
  requestOptions?: RequestOptions
): Promise<ApiResponse<ReserveSummary>>
```

## Parameters

| Parameter | Type | Tags | Description |
|  --- | --- | --- | --- |
| `requestOptions` | `RequestOptions \| undefined` | Optional | Pass additional request options. |

## Response Type

**200**: Summary

This method returns an [`ApiResponse`](../../doc/api-response.md) instance. The `result` property of this instance returns the response data which is of type [`ReserveSummary`](../../doc/models/reserve-summary.md).

## Example Usage

```ts
try {
  const response = await reserveController.getReserveSummary();

  // Extracting fully parsed response body.
  console.log(response.result);

  // Extracting response status code.
  console.log(response.statusCode);
  // Extracting response headers.
  console.log(response.headers);
  // Extracting response body of type `string | Stream`
  console.log(response.body);
} catch (error) {
  if (error instanceof ApiError) {
    // Extracting response error status code.
    console.log(error.statusCode);
    // Extracting response error headers.
    console.log(error.headers);
    // Extracting response error body of type `string | Stream`.
    console.log(error.body);
    if (error instanceof ProblemError) {
      console.log(error.result);
    }
  }
}
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
| 429 | rate-limited. Too many requests from this address. Wait `Retry-After` seconds, then retry. | [`ProblemError`](../../doc/models/problem-error.md) |


# Create Donation Intent

Public. The server remembers whether the donor wants their display name shown (default: anonymous) and
returns a `customId` of the form `reserve-donation:{uuid}`. The web page passes it to PayPal as
`custom_id`; the capture webhook carries it back. No PayPal credential is returned. The same
`Idempotency-Key` with the same body returns the same `customId`. The same key with a different body
returns 409 `idempotency-key-reused`.

```ts
async createDonationIntent(
  idempotencyKey: string,
  body: DonationIntentRequest,
  requestOptions?: RequestOptions
): Promise<ApiResponse<DonationIntent>>
```

## Parameters

| Parameter | Type | Tags | Description |
|  --- | --- | --- | --- |
| `idempotencyKey` | `string` | Header, Required | A unique value for this user action, for example a UUID. Retrying with the same key and body is safe and returns the same result.<br><br>**Constraints**: *Maximum Length*: `128` |
| `body` | [`DonationIntentRequest`](../../doc/models/donation-intent-request.md) | Body, Required | - |
| `requestOptions` | `RequestOptions \| undefined` | Optional | Pass additional request options. |

## Response Type

**201**: Intent

This method returns an [`ApiResponse`](../../doc/api-response.md) instance. The `result` property of this instance returns the response data which is of type [`DonationIntent`](../../doc/models/donation-intent.md).

## Example Usage

```ts
const idempotencyKey = '6f1c2b7e-3d4a-4e5f-9a10-b2c3d4e5f607';

const body: DonationIntentRequest = {
  anonymous: false,
  displayName: 'Maria',
};

try {
  const response = await reserveController.createDonationIntent(
    idempotencyKey,
    body
  );

  // Extracting fully parsed response body.
  console.log(response.result);

  // Extracting response status code.
  console.log(response.statusCode);
  // Extracting response headers.
  console.log(response.headers);
  // Extracting response body of type `string | Stream`
  console.log(response.body);
} catch (error) {
  if (error instanceof ApiError) {
    // Extracting response error status code.
    console.log(error.statusCode);
    // Extracting response error headers.
    console.log(error.headers);
    // Extracting response error body of type `string | Stream`.
    console.log(error.body);
    if (error instanceof ProblemError) {
      console.log(error.result);
    }
  }
}
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
| 400 | validation-failed. The body is not valid, or the Idempotency-Key header is missing. | [`ProblemError`](../../doc/models/problem-error.md) |
| 409 | idempotency-key-reused. The key was already used with a different body. | [`ProblemError`](../../doc/models/problem-error.md) |
| 429 | rate-limited. Too many requests from this address. Wait `Retry-After` seconds, then retry. | [`ProblemError`](../../doc/models/problem-error.md) |

