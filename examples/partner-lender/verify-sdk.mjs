// The lender check, through the APIMatic-generated TypeScript SDK (apimatic/sdk/typescript).
//   node verify-sdk.mjs <share-url-or-token>
// Build the SDK once first: cd ../../apimatic/sdk/typescript && npm install && npm run build
import { ApiError, Client, Environment, RecordPublicController } from "../../apimatic/sdk/typescript/dist/esm/index.js";

const input = process.argv[2];
if (!input) {
  console.error("Usage: node verify-sdk.mjs <share-url-or-token>");
  process.exit(2);
}
// A share link ends in the token: .../app/record/<token>. Pass the token as it is.
const token = decodeURIComponent(input.trim().split("?")[0].replace(/\/+$/, "").split("/").pop() ?? "");

// Environment.Production is the first server in the spec: the Seed sandbox.
const client = new Client({ environment: Environment.Production });
const records = new RecordPublicController(client);

try {
  const { result } = await records.verifyRecord(token);
  console.log(result.valid ? "VALID record" : "NOT valid: the member has had a default since this was shared");
  console.log(`  level:            ${result.level}`);
  console.log(`  member since:     ${result.since ?? "not shared"}`);
  console.log(`  loans on plan:    ${result.loansRepaidOnPlan ?? "not shared"}`);
  console.log(`  on-time band:     ${result.onTimeBand ?? "not shared"}`);
  console.log(`  link expires:     ${result.expiresAt}`);
} catch (error) {
  if (error instanceof ApiError) {
    if (error.statusCode === 404) console.log("This link did not work. It may be wrong, expired or revoked; Seed does not say which.");
    else if (error.statusCode === 429) console.log(`Too many requests. Try again in ${error.headers?.["retry-after"] ?? "a minute"} seconds.`);
    else console.log(`Seed cannot verify right now (HTTP ${error.statusCode}). Try again later.`);
    process.exit(1);
  }
  throw error;
}
