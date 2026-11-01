import "server-only";

import type { StaffRole } from "@paypal-seed/api-client/types";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { cache } from "react";
import { z } from "zod";
import { env } from "./env";
import { signToken, verifyToken } from "./session-token";

/** Staff demo session: which persona this browser acts as. The backend enforces roles and separation of duties. */
export const SESSION_COOKIE = "seed_desk_session";
const MAX_AGE_SECONDS = 60 * 60 * 8;

const staffSessionSchema = z.object({
  kind: z.literal("staff"),
  staffId: z.uuid(),
  name: z.string().max(80),
  roles: z.array(z.enum(["OFFICER", "CHECKER", "ADMIN"])).min(1),
});

export type StaffSession = z.infer<typeof staffSessionSchema>;

export async function parseSession(token: string | undefined): Promise<StaffSession | null> {
  const parsed = staffSessionSchema.safeParse(await verifyToken(token, env.SESSION_SECRET));
  return parsed.success ? parsed.data : null;
}

export const getSession = cache(async (): Promise<StaffSession | null> => {
  const store = await cookies();
  return parseSession(store.get(SESSION_COOKIE)?.value);
});

/** The signed-in staff member; redirects to /login when signed out. Use in pages, layouts and Server Actions. */
export async function requireSession(): Promise<StaffSession> {
  const session = await getSession();
  if (!session) {
    redirect("/login");
  }
  return session;
}

export function hasRole(session: StaffSession, role: StaffRole): boolean {
  return session.roles.includes(role);
}

export async function startSession(session: StaffSession): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE, await signToken(staffSessionSchema.parse(session), env.SESSION_SECRET), {
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
