
# Reserve Summary

## Structure

`ReserveSummary`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `balanceCents` | `number` | Required | **Constraints**: `>= 0` |
| `totalDonatedCents` | `number` | Required | **Constraints**: `>= 0` |
| `donationCount` | `number` | Required | Gifts received, one per PayPal capture. A repeat donor counts each time: Seed keeps no donor identity to deduplicate on.<br><br>**Constraints**: `>= 0` |
| `totalPaidOutCents` | `number` | Required | Paid to loans on behalf of members<br><br>**Constraints**: `>= 0` |
| `membersCovered` | `number` | Required | **Constraints**: `>= 0` |
| `openExposureCents` | `number` | Required | Outstanding balance of all open loans<br><br>**Constraints**: `>= 0` |
| `coverageRatioBps` | `number \| null` | Required | balance over exposure in basis points; null without exposure<br><br>**Constraints**: `>= 0` |
| `supporters` | `string[]` | Required | Display names of the latest donors who opted in; never an email or amount<br><br>**Constraints**: *Maximum Items*: `10` |
| `asOf` | `string` | Required | - |

## Example

```ts
import { ReserveSummary } from 'paypal-seed-partner-apilib';

const reserveSummary: ReserveSummary = {
  balanceCents: 125000,
  totalDonatedCents: 175000,
  donationCount: 31,
  totalPaidOutCents: 50000,
  membersCovered: 3,
  openExposureCents: 900000,
  coverageRatioBps: 1388,
  supporters: [
    'Maria',
    'Tom'
  ],
  asOf: '2026-11-10',
};
```

