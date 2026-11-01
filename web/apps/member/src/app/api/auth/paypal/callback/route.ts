import { type NextRequest, NextResponse } from "next/server";
import { backend } from "@/lib/api-client";
import { APP_ORIGIN, cookieOptions, LOGIN_COOKIE, PAYPAL_CALLBACK, SESSION_COOKIE } from "@/lib/auth";

const REASONS: Record<string, string> = {
  "paypal-login-rejected": "rejected",
  "paypal-identity-refused": "identity",
  "account-suspended": "suspended",
};

function finish(path: string) {
  const response = NextResponse.redirect(`${APP_ORIGIN}${path}`, 303);
  response.cookies.delete({ name: LOGIN_COOKIE, ...cookieOptions });
  return response;
}

const failed = (reason: string) => finish(`/login?error=${reason}`);

/** PayPal sends the browser back here. The state check proves this browser is the one that started the login. */
export async function GET(request: NextRequest) {
  const [expectedState, verifier] = request.cookies.get(LOGIN_COOKIE)?.value.split(".") ?? [];
  const params = request.nextUrl.searchParams;
  if (!expectedState || !verifier || params.get("state") !== expectedState) return failed("expired");
  if (params.get("error")) return failed("cancelled");
  const code = params.get("code");
  if (!code) return failed("rejected");

  let exchange: Response;
  try {
    exchange = await backend("/internal/auth/paypal/exchange", process.env.SEED_INTERNAL_TOKEN ?? "", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, codeVerifier: verifier, redirectUri: PAYPAL_CALLBACK }),
    });
  } catch {
    return failed("unavailable");
  }
  if (!exchange.ok) {
    const problem = await exchange.json().catch(() => ({}));
    return failed(REASONS[problem.code] ?? "unavailable");
  }

  const { token, expiresAt } = await exchange.json();
  const response = finish("/");
  response.cookies.set(SESSION_COOKIE, token, { ...cookieOptions, expires: new Date(expiresAt) });
  return response;
}
