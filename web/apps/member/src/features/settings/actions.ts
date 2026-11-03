"use server";

import { ApiProblem, unwrap } from "@paypal-seed/api-client";
import { revalidatePath } from "next/cache";
import { unstable_rethrow } from "next/navigation";
import { friendlyProblem } from "@/features/lending/server/problems";
import { memberApi } from "@/server/api";
import { requireSession, startSession } from "@/server/session";
import { consentChangeSchema, profileSchema } from "./schema";

export type SettingsState =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string };

/** Saves the profile. Only what the member can change here is sent; the backend keeps everything else. */
export async function updateProfile(_previous: SettingsState, formData: FormData): Promise<SettingsState> {
  const parsed = profileSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Please check what you entered." };
  }
  const api = await memberApi();
  try {
    await unwrap(api.PATCH("/api/v1/members/me", { body: parsed.data }));
  } catch (error) {
    unstable_rethrow(error);
    console.error("[settings] profile update failed", error);
    return { status: "error", message: friendlyProblem(error, "We could not save that just now. Please try again.").message };
  }
  // The top bar reads the name from the session cookie, so keep it in step with the profile.
  const session = await requireSession();
  await startSession({ ...session, displayName: parsed.data.displayName.slice(0, 60) });
  revalidatePath("/app", "layout");
  return { status: "ok", message: "Saved." };
}

export type ConsentResult = { ok: true } | { ok: false; message: string };

/**
 * Agrees to, or withdraws, one consent. The member's ledger (identity) is the record they see. The income consent also
 * lives in underwriting, where the fair check enforces it, so it is changed in both places (ADR 0012 and 0018). Withdrawing
 * is immediate even though the backend also listens: the second call does not wait for an event to arrive.
 */
export async function changeConsent(type: string, intent: string): Promise<ConsentResult> {
  const parsed = consentChangeSchema.safeParse({ type, intent });
  if (!parsed.success) {
    return { ok: false, message: "We did not recognise that agreement." };
  }
  const api = await memberApi();
  const income = parsed.data.type === "INCOME_READ";
  const grant = parsed.data.intent === "grant";
  try {
    try {
      await unwrap(
        grant
          ? api.POST("/api/v1/members/me/consents", { body: { type: parsed.data.type } })
          : api.DELETE("/api/v1/members/me/consents/{type}", { params: { path: { type: parsed.data.type } } }),
      );
    } catch (error) {
      unstable_rethrow(error);
      // A demo member with no PayPal sign-in has no ledger; the income consent still has to be changed.
      if (!(income && error instanceof ApiProblem && error.code === "member-not-found")) {
        throw error;
      }
    }
    if (income) {
      await unwrap(grant ? api.POST("/api/v1/underwriting/consent", {}) : api.DELETE("/api/v1/underwriting/consent", {}));
    }
  } catch (error) {
    unstable_rethrow(error);
    console.error("[settings] consent change failed", error);
    return { ok: false, message: friendlyProblem(error, "We could not change that just now. Please try again.").message };
  }
  revalidatePath("/app/settings");
  revalidatePath("/app/apply");
  return { ok: true };
}
