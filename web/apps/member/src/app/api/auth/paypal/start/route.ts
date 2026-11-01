import { createHash, randomBytes } from "node:crypto";
import type { Route } from "next";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { LOGIN_COOKIE, PAYPAL_CALLBACK } from "@/features/auth/server/paypal-login";
import { env } from "@/server/env";

export const dynamic = "force-dynamic";

const SCOPE = "openid email profile https://uri.paypal.com/services/paypalattributes";
const TEN_MINUTES = 600;

/** Remembers this login attempt in a short-lived cookie, then sends the browser to PayPal. */
export async function GET() {
  // On this machine PayPal returns to 127.0.0.1, so a login started on localhost would lose its cookie.
  const host = (await headers()).get("host");
  if (!env.isProduction && host !== new URL(env.APP_ORIGIN).host) {
    redirect(`${env.APP_ORIGIN}/api/auth/paypal/start` as Route);
  }
  if (!env.PAYPAL_LOGIN_CLIENT_ID) {
    redirect("/login?error=unavailable");
  }

  const state = randomBytes(32).toString("base64url");
  const verifier = randomBytes(32).toString("base64url");
  const query = new URLSearchParams({
    flowEntry: "static",
    client_id: env.PAYPAL_LOGIN_CLIENT_ID,
    response_type: "code",
    scope: SCOPE,
    redirect_uri: PAYPAL_CALLBACK,
    state,
    code_challenge: createHash("sha256").update(verifier).digest("base64url"),
    code_challenge_method: "S256",
  });

  (await cookies()).set(LOGIN_COOKIE, `${state}.${verifier}`, {
    httpOnly: true,
    sameSite: "lax",
    secure: env.isProduction,
    path: "/",
    maxAge: TEN_MINUTES,
  });
  redirect(`${env.PAYPAL_WEB_URL}/signin/authorize?${query}` as Route);
}
