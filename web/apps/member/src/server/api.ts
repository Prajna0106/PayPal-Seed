import "server-only";

import { createInternalClient, createSeedClient, type InternalClient, type SeedClient } from "@paypal-seed/api-client";
import { signActorToken } from "@paypal-seed/api-client/actor-token";
import { redirect } from "next/navigation";
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
  const client = await memberApiWithoutRedirect(session.memberId);
  client.use({
    onResponse({ response }) {
      if (response.status === 401) {
        redirect("/session-ended");
      }
    },
  });
  return client;
});

export async function memberApiWithoutRedirect(memberId: string): Promise<SeedClient> {
  const secrets = env.SEED_ACTOR_TOKEN_SECRETS;
  return createSeedClient({
    baseUrl: env.SEED_API_URL,
    identity: secrets
      ? { kind: "actor", token: await signActorToken(secrets, { type: "member", id: memberId }) }
      : { kind: "member", memberId },
  });
}

/** Client for a specific member id, before a session exists (demo seeding only). */
export function memberApiFor(memberId: string): Promise<SeedClient> {
  return memberApiWithoutRedirect(memberId);
}

/** Service client for `/internal/**`. Server-only; used to finish a login and by the demo seeding on /login. */
export function internalApi(): InternalClient {
  return createInternalClient({ baseUrl: env.SEED_API_URL, token: env.SEED_INTERNAL_TOKEN });
}
