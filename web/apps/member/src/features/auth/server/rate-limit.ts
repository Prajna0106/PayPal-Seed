import "server-only";

import { headers } from "next/headers";

const WINDOW_MS = 60_000;
const MAX_ATTEMPTS = 10;
const attempts = new Map<string, { count: number; resetAt: number }>();

/**
 * Counts login attempts per address. The backend's lockout protects one account; this slows someone trying many.
 * It lives in this server's memory, so it resets on restart and counts per instance: enough for one web server.
 */
export async function tooManyAttempts(): Promise<boolean> {
  const forwarded = (await headers()).get("x-forwarded-for");
  // The last hop is the one our own proxy added; earlier ones are whatever the caller sent.
  const address = forwarded?.split(",").at(-1)?.trim() ?? "local";
  const now = Date.now();
  for (const [key, entry] of attempts) {
    if (entry.resetAt <= now) {
      attempts.delete(key);
    }
  }
  const entry = attempts.get(address);
  if (!entry) {
    attempts.set(address, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  entry.count += 1;
  return entry.count > MAX_ATTEMPTS;
}
