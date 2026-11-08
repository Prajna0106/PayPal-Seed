
# Problem Exception

RFC 9457 problem. Branch on `code`.

## Structure

`ProblemException`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `mtype` | `str` | Required | - |
| `title` | `str` | Required | - |
| `status` | `int` | Required | - |
| `detail` | `str` | Optional | - |
| `instance` | `str` | Optional | - |
| `code` | `str` | Required | - |
| `details` | `Any` | Optional | - |

## Example

```python
try:
    # make the API call
except ProblemException as e:
    print(e)
except APIException as e:
    print(e)
```

