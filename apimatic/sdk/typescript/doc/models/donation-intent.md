
# Donation Intent

## Structure

`DonationIntent`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `intentId` | `string` | Required | - |
| `customId` | `string` | Required | Pass to PayPal as custom_id<br><br>**Constraints**: *Pattern*: `^reserve-donation:[0-9a-f-]{36}$` |

## Example

```ts
import { DonationIntent } from 'paypal-seed-partner-apilib';

const donationIntent: DonationIntent = {
  intentId: '000014e2-0000-0000-0000-000000000000',
  customId: 'customId4',
};
```

