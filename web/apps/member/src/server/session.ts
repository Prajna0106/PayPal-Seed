import "server-only";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { cache } from "react";
import { z } from "zod";
import { env } from "./env";
import { signToken, verifyToken } from "./session-token";

/**
 * Which member this browser acts as, in a signed cookie set after Log in with PayPal or a demo sign-in. Each backend
 * call signs a short actor token from it (ADR 0016). A demo session stops working when demo sign-in is off.
 */
export const SESSION_COOKIE = "seed_session";
const MAX_AGE_SECONDS = 60 * 60 * 8;

const memberSessionSchema = z.object({
  kind: z.literal("member"),
  memberId: z.uuid(),
  displayName: z.string().max(60),
  demo: z.boolean().optional(),
});

export type MemberSession = z.infer<typeof memberSessionSchema>;

export async function parseSession(token: string | undefined): Promise<MemberSession | null> {
  const payload = await verifyToken(token, env.SESSION_SECRET);
  const parsed = memberSessionSchema.safeParse(payload);
  return parsed.success && (env.demoLogin || !parsed.data.demo) ? parsed.data : null;
}

/** The current member, or `null`. Deduplicated per request. */
export const getSession = cache(async (): Promise<MemberSession | null> => {
  const store = await cookies();
  return parseSession(store.get(SESSION_COOKIE)?.value);
});

/** The current member; redirects to /login when signed out. Use in pages, layouts and Server Actions. */
export async function requireSession(): Promise<MemberSession> {
  const session = await getSession();
  if (!session) {
    redirect("/login");
  }
  return session;
}

export async function startSession(session: MemberSession): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE, await signToken(memberSessionSchema.parse(session), env.SESSION_SECRET), {
    httpOnly: true,
    sameSite: "lax",
    secure: env.isProduction,
    path: "/",
    maxAge: MAX_AGE_SECONDS,
  });
}

export async function endSession(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE);
}
