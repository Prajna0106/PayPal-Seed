
# Problem Error

RFC 9457 problem. Branch on `code`.

## Structure

`ProblemError`

## Fields

| Name | Type | Tags | Description |
|  --- | --- | --- | --- |
| `type` | `string` | Required | - |
| `title` | `string` | Required | - |
| `status` | `number` | Required | - |
| `detail` | `string \| undefined` | Optional | - |
| `instance` | `string \| undefined` | Optional | - |
| `code` | `string` | Required | - |
| `details` | `unknown \| undefined` | Optional | - |

## Example

```ts
try {
  // make the API call
} catch (error) {
  if (error instanceof ProblemError) {
    console.log(error.result);
  }
}
```

