import "server-only";

import { createInternalClient, createSeedClient, type InternalClient, type SeedClient } from "@paypal-seed/api-client";
import { cache } from "react";
import { env } from "./env";
import { requireSession } from "./session";

/**
 * Backend client acting as the signed-in member. One per request; redirects to /login when signed out.
 *
 * ```ts
 * const api = await memberApi();
 * const loans = await unwrap(api.GET("/api/v1/loans"));
 * ```
 */
export const memberApi = cache(async (): Promise<SeedClient> => {
  const session = await requireSession();
  return createSeedClient({ baseUrl: env.SEED_API_URL, identity: { kind: "member", memberId: session.memberId } });
});

/** Client for a specific member id, before a session exists (demo seeding only). */
export function memberApiFor(memberId: string): SeedClient {
  return createSeedClient({ baseUrl: env.SEED_API_URL, identity: { kind: "member", memberId } });
}

/** Service client for `/internal/**`. Server-only; used by the demo seeding on /login. */
export function internalApi(): InternalClient {
  return createInternalClient({ baseUrl: env.SEED_API_URL, token: env.SEED_INTERNAL_TOKEN });
}
