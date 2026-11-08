/** Public API host shown in the snippets. A label only; set it to the real host when one is published. */
export const API_BASE = (process.env.NEXT_PUBLIC_PUBLIC_API_URL || "https://seed-api.example").replace(/\/+$/, "");

/** APIMatic developer portal, shown only when configured. */
export const PORTAL_URL = process.env.NEXT_PUBLIC_PORTAL_URL || undefined;

export const STEPS = [
  { title: "Member shares a link", body: "From their record, they choose what to show and copy a link." },
  { title: "Partner calls verifyRecord", body: "One GET with the token from the link. No account needed." },
  { title: "Partner gets only that", body: "Level, since, loans repaid on plan, on-time band, valid." },
] as const;

export const FACTS = [
  { label: "Generated SDKs", value: "TypeScript, Python, Java, C#, Go, PHP, Ruby" },
  { label: "Rate limit", value: "60 requests a minute" },
  { label: "Personal data", value: "None in any response" },
] as const;

export interface Snippet {
  id: string;
  label: string;
  generated: boolean;
  code: string;
}

export const SNIPPETS: ReadonlyArray<Snippet> = [
  {
    id: "curl",
    label: "cURL",
    generated: false,
    code: `curl "${API_BASE}/api/v1/records/verify/$TOKEN" \\
  -H "Accept: application/json"`,
  },
  {
    id: "typescript",
    label: "TypeScript",
    generated: true,
    code: `// Import from the SDK you generate with APIMatic.
import { Client, RecordsController } from "seed-sdk";

const client = new Client({ environment: "${API_BASE}" });
const records = new RecordsController(client);

const { result } = await records.verifyRecord(token);
console.log(result.level, result.valid);`,
  },
  {
    id: "python",
    label: "Python",
    generated: true,
    code: `# Import from the SDK you generate with APIMatic.
from seed_sdk.client import Client
from seed_sdk.controllers.records_controller import RecordsController

client = Client(base_url="${API_BASE}")
records = RecordsController(client)

result = records.verify_record(token)
print(result.body.level, result.body.valid)`,
  },
];

export const SAMPLE_200 = `{
  "valid": true,
  "level": "STEADY",
  "since": "2026-03-01",
  "loansRepaidOnPlan": 2,
  "onTimeBand": "95-100%",
  "issuedAt": "2026-11-08T09:30:00Z",
  "expiresAt": "2026-11-15T09:30:00Z"
}`;

export const SAMPLE_404 = `{
  "type": "https://paypalseed.dev/problems/not-found",
  "title": "Not found",
  "status": 404
}`;
