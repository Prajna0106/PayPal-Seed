import { unwrap } from "@paypal-seed/api-client";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { NextRequest } from "next/server";
import { identifyWithPayPal, LOGIN_COOKIE, PayPalLoginError } from "@/features/auth/server/paypal-login";
import { internalApi } from "@/server/api";
import { startSession } from "@/server/session";

/** PayPal sends the browser back here. The state check proves this browser is the one that started the login. */
export async function GET(request: NextRequest) {
  const store = await cookies();
  const [expectedState, verifier] = store.get(LOGIN_COOKIE)?.value.split(".") ?? [];
  store.delete(LOGIN_COOKIE);

  const params = request.nextUrl.searchParams;
  const code = params.get("code");
  if (!expectedState || !verifier || params.get("state") !== expectedState) {
    redirect("/login?error=expired");
  }
  if (params.get("error")) {
    redirect("/login?error=cancelled");
  }
  if (!code) {
    redirect("/login?error=rejected");
  }

  const failure = await logIn(code, verifier);
  redirect(failure ? `/login?error=${failure}` : "/app");
}

/** Returns why the login failed, or nothing once the session has started. */
async function logIn(code: string, verifier: string): Promise<string | undefined> {
  try {
    const identity = await identifyWithPayPal(code, verifier);
    const { member } = await unwrap(internalApi().POST("/internal/members/login", { body: identity }));
    await startSession({
      kind: "member",
      memberId: member.memberId,
      displayName: member.displayName?.slice(0, 60) || "Member",
    });
    return undefined;
  } catch (error) {
    return error instanceof PayPalLoginError ? error.reason : "unavailable";
  }
}
