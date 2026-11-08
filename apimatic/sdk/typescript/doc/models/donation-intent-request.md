
# Donation Intent Request

## Structure

`DonationIntentRequest`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `anonymous` | `boolean \| undefined` | Optional | **Default**: `true` |
| `displayName` | `string \| undefined` | Optional | Shown on the public summary only when anonymous is false<br><br>**Constraints**: *Maximum Length*: `40` |

## Example

```ts
import { DonationIntentRequest } from 'paypal-seed-partner-apilib';

const donationIntentRequest: DonationIntentRequest = {
  anonymous: true,
  displayName: 'displayName6',
};
```

