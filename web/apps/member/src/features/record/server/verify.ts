import "server-only";

import type { VerifiedRecord } from "../model";
import { publicApi } from "./public-api";

const MAX_TOKEN_LENGTH = 400;

/**
 * Asks the backend whether a share token is good. Every failure (unknown, expired, revoked, tampered, malformed, or
 * the backend being unreachable) gives `null`: the page says the same thing for all of them, on purpose.
 */
export async function verifyShare(token: string): Promise<VerifiedRecord | null> {
  if (!token || token.length > MAX_TOKEN_LENGTH) {
    return null;
  }
  try {
    const { data, response } = await publicApi().GET("/api/v1/records/verify/{token}", { params: { path: { token } } });
    return response.ok && data ? data : null;
  } catch {
    // Never log the token: it is a bearer secret.
    return null;
  }
}
