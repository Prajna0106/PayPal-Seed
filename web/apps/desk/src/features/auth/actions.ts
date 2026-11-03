"use server";

import { ApiProblem, isProblem, unwrap } from "@paypal-seed/api-client";
import type { StaffRole } from "@paypal-seed/api-client/types";
import type { Route } from "next";
import { redirect } from "next/navigation";
import { z } from "zod";
import { internalLoginApi, staffApi } from "@/server/api";
import { env } from "@/server/env";
import { personaById } from "@/server/personas";
import { endSession, startSession } from "@/server/session";
import { tooManyAttempts } from "./server/rate-limit";

export interface LoginState {
  error?: string;
}

export interface PasswordState {
  error?: string;
  done?: boolean;
}

const LOGIN_FAILED = "That didn't work. Check your details and try again. After five wrong passwords, the account locks for 15 minutes.";
const REFUSED = "We couldn't log you in. If this keeps happening, please contact an admin.";
const UNAVAILABLE = "We can't log you in right now. Please try again in a few minutes.";
const SLOW_DOWN = "Too many attempts. Please wait a minute and try again.";

const localPath = z
  .string()
  .regex(/^\/(?!\/)[\w\-/]*$/, "Only local paths")
  .optional()
  .catch(undefined);

const personaForm = z.object({ personaId: z.uuid(), next: localPath });
const passwordForm = z.object({ email: z.email().max(254), password: z.string().min(1).max(128), next: localPath });
const changeForm = z.object({ currentPassword: z.string().min(1).max(128), newPassword: z.string().min(12).max(128) });

interface LoggedIn {
  staffId: string;
  displayName: string;
  roles: StaffRole[];
}

async function beginSession(staff: LoggedIn): Promise<void> {
  await startSession({
    kind: "staff",
    staffId: staff.staffId,
    name: staff.displayName.slice(0, 80),
    roles: staff.roles,
  });
}

function loginError(error: unknown): string {
  if (isProblem(error, "staff-login-refused")) {
    return LOGIN_FAILED;
  }
  if (!(error instanceof ApiProblem)) {
    console.warn(`[staff-login] login failed: ${error instanceof Error ? error.name : "unknown"}`);
    return UNAVAILABLE;
  }
  console.warn(`[staff-login] login returned HTTP ${error.status} (${error.code})`);
  return error.status >= 500 ? UNAVAILABLE : REFUSED;
}

export async function signInWithPassword(_previous: LoginState, formData: FormData): Promise<LoginState> {
  const parsed = passwordForm.safeParse({
    email: String(formData.get("email") ?? "").trim(),
    password: String(formData.get("password") ?? ""),
    next: formData.get("next") ?? undefined,
  });
  if (!parsed.success) {
    return { error: LOGIN_FAILED };
  }
  if (await tooManyAttempts()) {
    return { error: SLOW_DOWN };
  }
  try {
    const staff = await unwrap(
      internalLoginApi().POST("/internal/auth/staff/password/login", {
        body: { email: parsed.data.email, password: parsed.data.password },
      }),
    );
    await beginSession(staff);
  } catch (error) {
    return { error: loginError(error) };
  }
  // `next` was validated above as a local path, so it is safe to treat as a route of this app.
  redirect((parsed.data.next ?? "/") as Route);
}

export async function changePassword(_previous: PasswordState, formData: FormData): Promise<PasswordState> {
  const parsed = changeForm.safeParse({
    currentPassword: String(formData.get("currentPassword") ?? ""),
    newPassword: String(formData.get("newPassword") ?? ""),
  });
  if (!parsed.success) {
    return { error: "Your new password needs 12 or more characters." };
  }
  try {
    const api = await staffApi();
    await unwrap(api.POST("/api/v1/auth/staff/password", { body: parsed.data }));
    return { done: true };
  } catch (error) {
    rethrowRedirect(error);
    return { error: isProblem(error, "current-password-wrong") ? "That isn't your current password." : UNAVAILABLE };
  }
}

/** A session that ended mid-action redirects to login by throwing, which these catch blocks must not swallow. */
function rethrowRedirect(error: unknown): void {
  if (typeof error === "object" && error !== null && "digest" in error
    && String((error as { digest: unknown }).digest).startsWith("NEXT_REDIRECT")) {
    throw error;
  }
}

/** Signs in (or switches) to one of the fixed demo personas. */
export async function signInAs(formData: FormData): Promise<void> {
  const parsed = personaForm.safeParse({ personaId: formData.get("personaId"), next: formData.get("next") ?? undefined });
  const persona = parsed.success ? personaById(parsed.data.personaId) : undefined;
  if (!env.demoLogin || !parsed.success || !persona) {
    redirect("/login");
  }
  await startSession({ kind: "staff", staffId: persona.id, name: persona.name, roles: [...persona.roles], demo: true });
  // `next` was validated above as a local path, so it is safe to treat as a route of this app.
  redirect((parsed.data.next ?? "/") as Route);
}

export async function signOut(): Promise<void> {
  await endSession();
  redirect("/login");
}
