import { NextResponse, type NextRequest } from "next/server";
import { parseSession, SESSION_COOKIE } from "@/server/session";

/**
 * Optimistic check only: the member area needs a valid signed session. Pages still call `requireSession()`, and the
 * backend checks ownership on every request.
 */
export async function proxy(request: NextRequest) {
  const session = await parseSession(request.cookies.get(SESSION_COOKIE)?.value);
  if (!session) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", request.nextUrl.pathname);
    return NextResponse.redirect(login);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/app/:path*"],
};
