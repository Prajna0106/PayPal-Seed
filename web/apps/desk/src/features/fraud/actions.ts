"use server";

import { isProblem, unwrap } from "@paypal-seed/api-client";
import { refresh } from "next/cache";
import { z } from "zod";
import { fraudErrorMessage } from "./model";
import { reviewSchema } from "./schema";
import { newIdempotencyKey } from "./server/keys";
import { hasRole, requireSession } from "@/server/session";
import { staffApi } from "@/server/api";

export type ReviewResult =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string; nextKey: string };

/**
 * Clears or confirms a flag, once. The backend writes the audit entry; a confirmation needs a note. Reviewing never
 * changes what the member can do: a flag is advice for officers.
 */
export async function reviewFlag(_previous: ReviewResult, formData: FormData): Promise<ReviewResult> {
  const session = await requireSession();
  // Handed back with every answer so the form keeps using it until a conflict asks for a fresh one.
  const submitted = z.uuid().safeParse(formData.get("key")).success ? String(formData.get("key")) : newIdempotencyKey();
  if (!hasRole(session, "OFFICER") && !hasRole(session, "ADMIN")) {
    return { status: "error", message: "Only an officer or an admin can review a flag. Switch persona (bottom left).", nextKey: submitted };
  }
  const parsed = reviewSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form.", nextKey: submitted };
  }
  const { flagId, decision, note, key } = parsed.data;
  if (decision === "CONFIRM" && !note) {
    return { status: "error", message: "Add a note that explains why you are confirming this flag.", nextKey: submitted };
  }
  try {
    const api = await staffApi();
    await unwrap(
      api.POST("/api/v1/desk/fraud/flags/{flagId}/review", {
        params: { path: { flagId }, header: { "Idempotency-Key": key } },
        body: { decision, ...(note ? { note } : {}) },
      }),
    );
    refresh();
    return {
      status: "ok",
      message: decision === "CLEAR" ? "Flag cleared. The decision is in the audit log." : "Flag confirmed. The decision is in the audit log.",
    };
  } catch (error) {
    // A conflict means another officer got there first or the key was used for other details: re-read the page.
    const conflict = isProblem(error) && error.status === 409;
    if (conflict) {
      refresh();
    }
    return { status: "error", message: fraudErrorMessage(error), nextKey: conflict ? newIdempotencyKey() : submitted };
  }
}
