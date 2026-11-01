import "server-only";

import { createInternalClient, createSeedClient, type InternalClient, type SeedClient } from "@paypal-seed/api-client";
import { cache } from "react";
import { env } from "./env";
import { hasRole, requireSession } from "./session";

/**
 * Backend client acting as the signed-in staff member. One per request.
 *
 * ```ts
 * const api = await staffApi();
 * const queue = await unwrap(api.GET("/api/v1/desk/hardship/cases", { params: { query: { status: ["PENDING_APPROVAL"] } } }));
 * ```
 */
export const staffApi = cache(async (): Promise<SeedClient> => {
  const session = await requireSession();
  return createSeedClient({
    baseUrl: env.SEED_API_URL,
    identity: { kind: "staff", staffId: session.staffId, roles: session.roles },
  });
});

/**
 * Service client for `/internal/**` (operations page). ADMIN only: the internal token never leaves the server.
 * Pages check `hasRole(session, "ADMIN")` first and explain the restriction; this guard stops any other caller.
 */
export async function adminInternalApi(): Promise<InternalClient> {
  const session = await requireSession();
  if (!hasRole(session, "ADMIN")) {
    throw new Error("Operations need the ADMIN role.");
  }
  return createInternalClient({ baseUrl: env.SEED_API_URL, token: env.SEED_INTERNAL_TOKEN });
}
