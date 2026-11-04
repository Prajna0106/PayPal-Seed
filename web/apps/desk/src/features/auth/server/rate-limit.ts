import "server-only";

import { headers } from "next/headers";
import { env } from "@/server/env";

const WINDOW_MS = 60_000;
const MAX_ATTEMPTS = 10;
const MAX_TRACKED = 10_000;
const attempts = new Map<string, { count: number; resetAt: number }>();
let nextSweep = 0;

/**
 * The caller's address. Each trusted proxy appends the address it saw, so the client is `SEED_TRUSTED_PROXY_HOPS`
 * places from the end; anything before that is whatever the caller sent.
 */
export async function clientAddress(): Promise<string> {
  const chain = ((await headers()).get("x-forwarded-for") ?? "").split(",").map((hop) => hop.trim()).filter(Boolean);
  if (env.SEED_TRUSTED_PROXY_HOPS === 0 || chain.length === 0) {
    return "local";
  }
  return chain.at(-env.SEED_TRUSTED_PROXY_HOPS) ?? chain[0] ?? "local";
}

/**
 * Slows one address trying many logins; the backend's lockout protects each account. It lives in this server's
 * memory, so it resets on restart and counts per instance. When it is full it refuses, rather than forgetting.
 */
export async function tooManyAttempts(): Promise<boolean> {
  const address = await clientAddress();
  const now = Date.now();
  if (now >= nextSweep || attempts.size >= MAX_TRACKED) {
    for (const [key, entry] of attempts) {
      if (entry.resetAt <= now) {
        attempts.delete(key);
      }
    }
    nextSweep = now + WINDOW_MS;
  }
  const entry = attempts.get(address);
  if (!entry) {
    if (attempts.size >= MAX_TRACKED) {
      return true;
    }
    attempts.set(address, { count: 1, resetAt: now + WINDOW_MS });
    return false;
  }
  entry.count += 1;
  return entry.count > MAX_ATTEMPTS;
}
