
# Donation Intent

## Structure

`DonationIntent`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `intent_id` | `uuid\|str` | Required | - |
| `custom_id` | `str` | Required | Pass to PayPal as custom_id<br><br>**Constraints**: *Pattern*: `^reserve-donation:[0-9a-f-]{36}$` |

## Example

```python
from paypalseedpartnerapi.models.donation_intent import DonationIntent

donation_intent = DonationIntent(
    intent_id='00001cdc-0000-0000-0000-000000000000',
    custom_id='customId8'
)
```

