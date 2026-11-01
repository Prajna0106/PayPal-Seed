import { cookies } from "next/headers";
import { cache } from "react";
import { backend } from "./api-client";

const production = process.env.NODE_ENV === "production";

export const APP_ORIGIN = process.env.APP_ORIGIN ?? "http://127.0.0.1:3000";
export const PAYPAL_CALLBACK = `${APP_ORIGIN}/api/auth/paypal/callback`;

// The __Host- prefix makes browsers insist on Secure, so it is off for plain http on this machine.
export const SESSION_COOKIE = production ? "__Host-seed_session" : "seed_session";
export const LOGIN_COOKIE = production ? "__Host-seed_login" : "seed_login";

export const cookieOptions = {
  httpOnly: true,
  secure: production,
  sameSite: "lax",
  path: "/",
} as const;

export type Member = { id: string; displayName?: string };

/** Who this request belongs to, or null if they aren't logged in. The backend decides, not the cookie's presence. */
export const currentMember = cache(async (): Promise<Member | null> => {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!token) return null;
  const response = await backend("/api/v1/auth/me", token);
  if (response.status === 401) return null;
  if (!response.ok) throw new Error(`Session check failed: HTTP ${response.status}`);
  return response.json();
});
