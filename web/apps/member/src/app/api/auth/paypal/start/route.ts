import { createHash, randomBytes } from "node:crypto";
import { NextResponse } from "next/server";
import { cookieOptions, LOGIN_COOKIE, PAYPAL_CALLBACK } from "@/lib/auth";

export const dynamic = "force-dynamic";

const PAYPAL_WEB_URL = process.env.PAYPAL_WEB_URL ?? "https://www.sandbox.paypal.com";
const SCOPE = "openid email profile https://uri.paypal.com/services/paypalattributes";

/** Remembers this login attempt in a short-lived cookie, then sends the browser to PayPal. */
export function GET() {
  const state = randomBytes(32).toString("base64url");
  const verifier = randomBytes(32).toString("base64url");
  const query = new URLSearchParams({
    flowEntry: "static",
    client_id: process.env.PAYPAL_LOGIN_CLIENT_ID ?? "",
    response_type: "code",
    scope: SCOPE,
    redirect_uri: PAYPAL_CALLBACK,
    state,
    code_challenge: createHash("sha256").update(verifier).digest("base64url"),
    code_challenge_method: "S256",
  });
  const response = NextResponse.redirect(`${PAYPAL_WEB_URL}/signin/authorize?${query}`, 303);
  response.cookies.set(LOGIN_COOKIE, `${state}.${verifier}`, { ...cookieOptions, maxAge: 600 });
  return response;
}
