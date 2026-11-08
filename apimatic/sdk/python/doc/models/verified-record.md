
# Verified Record

Fields beyond `level` appear only when the member chose a wider `detail`.

## Structure

`VerifiedRecord`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `valid` | `bool` | Required | - |
| `level` | [`RecordLevelEnum`](../../doc/models/record-level-enum.md) | Required | The member's credit-record level, lowest to highest: NEW, BUILDING, STEADY, TRUSTED, EXEMPLARY. |
| `since` | `date` | Optional | First day of the month the record began (SUMMARY and FULL) |
| `loans_repaid_on_plan` | `int` | Optional | SUMMARY and FULL<br><br>**Constraints**: `>= 0` |
| `loans_completed_with_fund_help` | `int` | Optional | SUMMARY and FULL. Loans the community safety fund paid in full; they are not in loansRepaidOnPlan. A count only: it says nothing about why help was needed.<br><br>**Constraints**: `>= 0` |
| `on_time_band` | [`OnTimeBandEnum`](../../doc/models/on-time-band-enum.md) | Optional | The on-time share of saving months and instalments, as a band |
| `issued_at` | `datetime` | Required | - |
| `expires_at` | `datetime` | Required | - |

## Example

```python
import dateutil.parser

from paypalseedpartnerapi.models.on_time_band_enum import OnTimeBandEnum
from paypalseedpartnerapi.models.record_level_enum import RecordLevelEnum
from paypalseedpartnerapi.models.verified_record import VerifiedRecord

verified_record = VerifiedRecord(
    valid=False,
    level=RecordLevelEnum.EXEMPLARY,
    issued_at=dateutil.parser.parse('2016-03-13T12:52:32.123Z'),
    expires_at=dateutil.parser.parse('2016-03-13T12:52:32.123Z'),
    since=dateutil.parser.parse('2016-03-13').date(),
    loans_repaid_on_plan=34,
    loans_completed_with_fund_help=80,
    on_time_band=OnTimeBandEnum.ENUM_BELOW_70
)
```

