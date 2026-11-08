
# Donation Intent Request

## Structure

`DonationIntentRequest`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `anonymous` | `bool` | Optional | **Default**: `True` |
| `display_name` | `str` | Optional | Shown on the public summary only when anonymous is false<br><br>**Constraints**: *Maximum Length*: `40` |

## Example

```python
from paypalseedpartnerapi.models.donation_intent_request import DonationIntentRequest

donation_intent_request = DonationIntentRequest(
    anonymous=True,
    display_name='displayName4'
)
```

