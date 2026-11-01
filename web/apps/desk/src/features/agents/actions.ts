"use server";

import { isProblem, problemMessage, unwrap } from "@paypal-seed/api-client";
import { refresh } from "next/cache";
import { z } from "zod";
import type { ActionResult } from "@/features/hardship/actions";
import { staffApi } from "@/server/api";
import { requireSession } from "@/server/session";

const form = z.object({
  scope: z.enum(["GLOBAL", "guardian"]),
  mode: z.enum(["LIVE", "SHADOW", "TEMPLATES_ONLY"], { message: "Choose a mode." }),
  reason: z.string().trim().min(3, "Give a short reason; it goes into the audit history.").max(500),
});

/**
 * Changes the kill switch. Any officer can make the agents safer at once; moving back to a less restrictive mode
 * needs an admin, and the backend enforces that.
 */
export async function setKillSwitch(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = form.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form." };
  }
  try {
    const api = await staffApi();
    const state = await unwrap(api.POST("/api/v1/desk/agents/kill-switch", { body: parsed.data }));
    refresh();
    return { status: "ok", message: `Guardian is now ${state.mode === "TEMPLATES_ONLY" ? "on templates only" : (state.mode ?? "").toLowerCase()}.` };
  } catch (error) {
    if (isProblem(error, "kill-switch-up-needs-admin")) {
      return { status: "error", code: error.code, message: "Only an admin can move the agents to a less restrictive mode." };
    }
    return {
      status: "error",
      message: isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend.",
    };
  }
}
