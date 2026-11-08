# Partner lender check

A small script that plays the partner lender: a member shares a Seed record link, the lender verifies it through the public `verifyRecord` route.

This is the **plain HTTP version**. It uses `fetch` only. Node 20 or newer. No install, no build.

```bash
node verify.mjs https://<seed-app>/app/record/<token>
node verify.mjs <token>
```

By default it calls the sandbox, `https://seed-api-4hui.onrender.com`. Set `SEED_API_URL` to use another server, for example `SEED_API_URL=http://localhost:8080`.

The sandbox is on a free host. The first call after idle time can take up to a minute.

## What it prints

A short decision summary: whether the record is still valid, the level, the optional summary fields (shown as "not shared" when the member left them out), and when the link expires.

| Answer | Message |
| --- | --- |
| 200 | The summary above |
| 404 | The link did not work (wrong, expired or revoked; Seed does not say which) |
| 429 | Too many requests, with the `Retry-After` seconds |
| 503 | Seed cannot verify right now |

The script does not store anything and does not print the token.

## Using the APIMatic-generated TypeScript SDK

The repository does not include a generated SDK. You generate one from `apimatic/src/spec/seed-partner/seed-partner-api.yaml`:

1. Install the CLI: `npm i -g @apimatic/cli@1`, then `apimatic auth login`.
2. Run `apimatic quickstart` and choose the SDK path, or run `apimatic sdk generate -l typescript -i <folder containing src>` (the folder with `src/spec/...`; that is `apimatic/` in this repo).
3. Or, in the APIMatic dashboard, import the spec file and generate the TypeScript SDK there.
4. Install the generated package in a project and use it.

Placeholder code. Adjust names to the generated package; the real class, config and method names come from the SDK you generate.

```typescript
// PLACEHOLDER: names depend on the generated package.
import { Client, RecordPublicController, ApiError } from "seed-partner-sdk";

const client = new Client({ environment: "production" }); // sandbox server
const records = new RecordPublicController(client);

try {
  const { result } = await records.verifyRecord(token);
  console.log(result.valid, result.level, result.onTimeBand);
} catch (e) {
  if (e instanceof ApiError && e.statusCode === 404) console.log("This link did not work.");
  else throw e;
}
```
