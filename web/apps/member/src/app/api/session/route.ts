import { type NextRequest, NextResponse } from "next/server";
import { backend } from "@/lib/api-client";
import { APP_ORIGIN, cookieOptions, SESSION_COOKIE } from "@/lib/auth";

/** Logs out. The Origin check stops another website from logging a member out, since the cookie is SameSite=Lax. */
export async function DELETE(request: NextRequest) {
  if (request.headers.get("origin") !== APP_ORIGIN) {
    return NextResponse.json({ code: "forbidden" }, { status: 403 });
  }
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  if (token) {
    await backend("/api/v1/auth/logout", token, { method: "POST" }).catch(() => undefined);
  }
  const response = new NextResponse(null, { status: 204 });
  response.cookies.delete({ name: SESSION_COOKIE, ...cookieOptions });
  return response;
}
