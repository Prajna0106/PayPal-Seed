import { NextResponse, type NextRequest } from "next/server";
import { parseSession, SESSION_COOKIE } from "@/server/session";

const ALLOWED_BEFORE_PASSWORD_CHANGE = ["/account", "/api/auth/", "/session-ended"];

/**
 * Optimistic check: every desk page needs a signed staff session, and someone whose password an admin chose must
 * change it first. Pages and the backend still check roles; the backend gives that person no roles until they do.
 */
export async function proxy(request: NextRequest) {
  const session = await parseSession(request.cookies.get(SESSION_COOKIE)?.value);
  const path = request.nextUrl.pathname;
  if (!session) {
    const login = new URL("/login", request.url);
    if (path !== "/") {
      login.searchParams.set("next", path);
    }
    return NextResponse.redirect(login);
  }
  if (session.passwordChangeRequired && !ALLOWED_BEFORE_PASSWORD_CHANGE.some((allowed) => path.startsWith(allowed))) {
    return NextResponse.redirect(new URL("/account", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!login|_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|ico|webp)$).*)"],
};
