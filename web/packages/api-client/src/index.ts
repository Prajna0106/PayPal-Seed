import "server-only";

import createClient, { type Client } from "openapi-fetch";
import { ApiProblem, type ProblemBody } from "./problem";
import type { InternalPaths, SeedIdentity, SeedPaths } from "./types";

export { ApiProblem, isProblem, problemMessage, PROBLEM_MESSAGES } from "./problem";
export type * from "./types";

export type SeedClient = Client<SeedPaths>;
export type InternalClient = Client<InternalPaths>;

export interface ClientOptions {
  baseUrl: string;
  identity: SeedIdentity;
  /** Per-request timeout; the backend answers reads in well under a second. */
  timeoutMs?: number;
}

/** Headers the backend reads identity from. Built only on the server; never sent to the browser. */
export function identityHeaders(identity: SeedIdentity): Record<string, string> {
  switch (identity.kind) {
    case "session":
      return { Authorization: `Bearer ${identity.token}` };
    case "member":
      return { "X-Seed-Member-Id": identity.memberId };
    case "staff":
      return { "X-Seed-Staff-Id": identity.staffId, "X-Seed-Staff-Roles": identity.roles.join(",") };
    case "internal":
      return { Authorization: `Bearer ${identity.token}` };
    case "anonymous":
      return {};
  }
}

function timedFetch(timeoutMs: number): (input: Request) => Promise<Response> {
  return (input) => fetch(new Request(input, { signal: AbortSignal.timeout(timeoutMs) }));
}

/** Client for the public, member and desk API (`contracts/openapi/seed-api.yaml`). Create one per request. */
export function createSeedClient({ baseUrl, identity, timeoutMs = 10_000 }: ClientOptions): SeedClient {
  return createClient<SeedPaths>({ baseUrl, headers: identityHeaders(identity), fetch: timedFetch(timeoutMs) });
}

/** Client for `/internal/**` jobs (`contracts/openapi/internal-jobs.yaml`). Service token only. */
export function createInternalClient({ baseUrl, token, timeoutMs = 30_000 }: { baseUrl: string; token: string; timeoutMs?: number }): InternalClient {
  return createClient<InternalPaths>({
    baseUrl,
    headers: identityHeaders({ kind: "internal", token }),
    fetch: timedFetch(timeoutMs),
  });
}

/** `{ headers: { "Idempotency-Key": key } }` for a write; reuse the same key when the user retries the action. */
export function idempotent(key: string): { headers: { "Idempotency-Key": string } } {
  return { headers: { "Idempotency-Key": key } };
}

interface FetchResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/**
 * Turns an openapi-fetch result into its data, or throws an {@link ApiProblem}. Use it so every page handles errors
 * the same way:
 *
 * ```ts
 * const loans = await unwrap(api.GET("/api/v1/loans"));
 * ```
 */
export async function unwrap<T>(pending: Promise<FetchResult<T>>): Promise<T> {
  const { data, error, response } = await pending;
  if (!response.ok || error !== undefined) {
    throw new ApiProblem(response.status, isObject(error) ? (error as ProblemBody) : undefined);
  }
  return data as T;
}

/** Like {@link unwrap}, but a 404 becomes `null` (for "there is none yet" reads such as an open hardship offer). */
export async function unwrapOrNull<T>(pending: Promise<FetchResult<T>>): Promise<T | null> {
  try {
    return await unwrap(pending);
  } catch (error) {
    if (error instanceof ApiProblem && error.status === 404) {
      return null;
    }
    throw error;
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
