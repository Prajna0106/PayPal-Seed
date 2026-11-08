
# Verified Record

Fields beyond `level` appear only when the member chose a wider `detail`.

## Structure

`VerifiedRecord`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `valid` | `boolean` | Required | - |
| `level` | [`RecordLevelEnum`](../../doc/models/record-level-enum.md) | Required | The member's credit-record level, lowest to highest: NEW, BUILDING, STEADY, TRUSTED, EXEMPLARY. |
| `since` | `string \| undefined` | Optional | First day of the month the record began (SUMMARY and FULL) |
| `loansRepaidOnPlan` | `number \| undefined` | Optional | SUMMARY and FULL<br><br>**Constraints**: `>= 0` |
| `loansCompletedWithFundHelp` | `number \| undefined` | Optional | SUMMARY and FULL. Loans the community safety fund paid in full; they are not in loansRepaidOnPlan. A count only: it says nothing about why help was needed.<br><br>**Constraints**: `>= 0` |
| `onTimeBand` | [`OnTimeBandEnum \| undefined`](../../doc/models/on-time-band-enum.md) | Optional | The on-time share of saving months and instalments, as a band |
| `issuedAt` | `string` | Required | - |
| `expiresAt` | `string` | Required | - |

## Example

```ts
import {
  OnTimeBandEnum,
  RecordLevelEnum,
  VerifiedRecord,
} from 'paypal-seed-partner-apilib';

const verifiedRecord: VerifiedRecord = {
  valid: false,
  level: RecordLevelEnum.STEADY,
  issuedAt: '2016-03-13T12:52:32.123Z',
  expiresAt: '2016-03-13T12:52:32.123Z',
  since: '2016-03-13T12:52:32.123Z',
  loansRepaidOnPlan: 100,
  loansCompletedWithFundHelp: 14,
  onTimeBand: OnTimeBandEnum.Enum8594,
};
```

