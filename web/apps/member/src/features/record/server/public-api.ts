import "server-only";

import { createSeedClient, type SeedClient } from "@paypal-seed/api-client";
import { env } from "@/server/env";

/** Client for the routes that need no sign-in (partner verification, the safety fund). Sends no identity at all. */
export function publicApi(): SeedClient {
  return createSeedClient({ baseUrl: env.SEED_API_URL, identity: { kind: "anonymous" }, timeoutMs: 8_000 });
}
