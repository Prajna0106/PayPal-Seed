import "server-only";

import { createInternalClient, createSeedClient, type InternalClient, type SeedClient, unwrap } from "@paypal-seed/api-client";
import type { SeedIdentity } from "@paypal-seed/api-client/types";
import { signActorToken } from "@paypal-seed/api-client/actor-token";
import { redirect } from "next/navigation";
import { cache } from "react";
import { env } from "./env";
import { hasRole, requireSession, type StaffSession } from "./session";

/**
 * Backend client acting as the signed-in staff member. One per request.
 *
 * ```ts
 * const api = await staffApi();
 * const queue = await unwrap(api.GET("/api/v1/desk/hardship/cases", { params: { query: { status: ["PENDING_APPROVAL"] } } }));
 * ```
 */
export const staffApi = cache(async (): Promise<SeedClient> => {
  const client = await staffApiWithoutRedirect(await requireSession());
  client.use({
    onResponse({ response }) {
      if (response.status === 401) {
        redirect("/session-ended");
      }
    },
  });
  return client;
});

/** Who a backend call is made as: a signed staff token, or dev headers when no secret is set (dev and demo only). */
export async function staffIdentity(session: StaffSession): Promise<SeedIdentity> {
  const secrets = env.staffTokenSecrets;
  const claims = { type: "staff", id: session.staffId, roles: session.roles, authTime: session.startedAt } as const;
  return secrets
    ? { kind: "actor", token: await signActorToken(secrets, claims) }
    : { kind: "staff", staffId: session.staffId, roles: session.roles };
}

export async function staffApiWithoutRedirect(session: StaffSession): Promise<SeedClient> {
  return createSeedClient({ baseUrl: env.SEED_API_URL, identity: await staffIdentity(session) });
}

/** The staff login and account routes. Their token is the desk's alone: the member app can't reach them. */
export function staffAccountsApi(): InternalClient {
  return createInternalClient({ baseUrl: env.SEED_API_URL, token: env.SEED_STAFF_SERVICE_TOKEN });
}

/** What the directory says this person may do right now. */
export async function currentAccess() {
  return unwrap((await staffApi()).GET("/api/v1/auth/staff/me"));
}

/**
 * Service client for `/internal/**` (operations page). ADMIN only: the internal token never leaves the server.
 * Pages check `hasRole(session, "ADMIN")` first and explain the restriction; this guard stops any other caller. The
 * backend can't tell who sends the service token, so it's asked first whether this person is still an active admin.
 */
export async function adminInternalApi(): Promise<InternalClient> {
  const session = await requireSession();
  const access = await currentAccess();
  if (!hasRole(session, "ADMIN") || !access.roles.includes("ADMIN")) {
    throw new Error("Operations need the ADMIN role.");
  }
  return createInternalClient({ baseUrl: env.SEED_API_URL, token: env.SEED_INTERNAL_TOKEN });
}
