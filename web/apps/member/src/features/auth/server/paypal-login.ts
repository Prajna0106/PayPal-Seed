import "server-only";

import { z } from "zod";
import { env } from "@/server/env";

/** Remembers one login attempt between leaving for PayPal and coming back. */
export const LOGIN_COOKIE = "seed_login";

/** Must match a return URL saved on the PayPal app exactly. */
export const PAYPAL_CALLBACK = `${env.APP_ORIGIN}/api/auth/paypal/callback`;

const TIMEOUT_MS = 10_000;

/** Which message the login page shows. */
export class PayPalLoginError extends Error {
  constructor(readonly reason: "rejected" | "identity" | "unavailable") {
    super(`PayPal login failed: ${reason}`);
    this.name = "PayPalLoginError";
  }
}

const tokenSchema = z.object({ access_token: z.string().min(1) });
const userSchema = z.object({
  payer_id: z.string().min(1).max(64),
  email: z.email().max(254).optional().catch(undefined),
  name: z.string().optional().catch(undefined),
  verified_account: z.union([z.boolean(), z.string()]).optional().catch(undefined),
  locale: z.string().max(16).optional().catch(undefined),
});

export interface PayPalIdentity {
  paypalPayerId: string;
  email?: string;
  name?: string;
  verified: boolean;
  locale?: string;
}

/**
 * Log in with PayPal, server to server (ADR 0012). The token response has no ID token, so identity comes from userinfo.
 * The token carries the app's payment scopes, so it is dropped as soon as this returns. A code works once and a failed
 * attempt uses it up, so nothing here retries. Logs hold statuses and debug ids only: never codes, tokens or names.
 */
export async function identifyWithPayPal(code: string, codeVerifier: string): Promise<PayPalIdentity> {
  if (!env.PAYPAL_LOGIN_CLIENT_ID || !env.PAYPAL_LOGIN_CLIENT_SECRET) {
    throw new PayPalLoginError("unavailable");
  }
  const accessToken = await exchangeCode(code, codeVerifier, env.PAYPAL_LOGIN_CLIENT_ID, env.PAYPAL_LOGIN_CLIENT_SECRET);
  const response = await call("userinfo", "identity", `${env.PAYPAL_API_URL}/v1/identity/openidconnect/userinfo?schema=openid`, {
    headers: { Authorization: `Bearer ${accessToken}`, Accept: "application/json" },
  });
  const user = userSchema.safeParse(await response.json());
  if (!user.success) {
    console.warn("[paypal-login] userinfo had no usable payer id");
    throw new PayPalLoginError("unavailable");
  }
  return {
    paypalPayerId: user.data.payer_id,
    verified: user.data.verified_account === true || user.data.verified_account === "true",
    ...(user.data.email ? { email: user.data.email } : {}),
    ...(user.data.name ? { name: user.data.name.slice(0, 120) } : {}),
    ...(user.data.locale ? { locale: user.data.locale } : {}),
  };
}

async function exchangeCode(code: string, codeVerifier: string, clientId: string, clientSecret: string): Promise<string> {
  const response = await call("token", "rejected", `${env.PAYPAL_API_URL}/v1/oauth2/token`, {
    method: "POST",
    headers: {
      Authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString("base64")}`,
      "Content-Type": "application/x-www-form-urlencoded",
      Accept: "application/json",
    },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      code_verifier: codeVerifier,
      redirect_uri: PAYPAL_CALLBACK,
    }),
  });
  const token = tokenSchema.safeParse(await response.json());
  if (!token.success) {
    console.warn("[paypal-login] token response had no access token");
    throw new PayPalLoginError("unavailable");
  }
  return token.data.access_token;
}

/** A 4xx means PayPal said no; anything else means PayPal couldn't be reached or broke. */
async function call(step: string, whenRefused: "rejected" | "identity", url: string, init: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(url, { ...init, cache: "no-store", signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch (error) {
    console.warn(`[paypal-login] ${step} call failed: ${error instanceof Error ? error.name : "unknown"}`);
    throw new PayPalLoginError("unavailable");
  }
  if (!response.ok) {
    console.warn(`[paypal-login] ${step} call returned HTTP ${response.status} (debug id ${response.headers.get("paypal-debug-id")})`);
    throw new PayPalLoginError(response.status >= 400 && response.status < 500 ? whenRefused : "unavailable");
  }
  return response;
}
