
# Reserve Summary

## Structure

`ReserveSummary`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `balance_cents` | `int` | Required | **Constraints**: `>= 0` |
| `total_donated_cents` | `int` | Required | **Constraints**: `>= 0` |
| `donation_count` | `int` | Required | Gifts received, one per PayPal capture. A repeat donor counts each time: Seed keeps no donor identity to deduplicate on.<br><br>**Constraints**: `>= 0` |
| `total_paid_out_cents` | `int` | Required | Paid to loans on behalf of members<br><br>**Constraints**: `>= 0` |
| `members_covered` | `int` | Required | **Constraints**: `>= 0` |
| `open_exposure_cents` | `int` | Required | Outstanding balance of all open loans<br><br>**Constraints**: `>= 0` |
| `coverage_ratio_bps` | `int` | Required | balance over exposure in basis points; null without exposure<br><br>**Constraints**: `>= 0` |
| `supporters` | `List[str]` | Required | Display names of the latest donors who opted in; never an email or amount<br><br>**Constraints**: *Maximum Items*: `10` |
| `as_of` | `date` | Required | - |

## Example

```python
import dateutil.parser

from paypalseedpartnerapi.models.reserve_summary import ReserveSummary

reserve_summary = ReserveSummary(
    balance_cents=125000,
    total_donated_cents=175000,
    donation_count=31,
    total_paid_out_cents=50000,
    members_covered=3,
    open_exposure_cents=900000,
    coverage_ratio_bps=1388,
    supporters=[
        'Maria',
        'Tom'
    ],
    as_of=dateutil.parser.parse('2026-11-10').date()
)
```

