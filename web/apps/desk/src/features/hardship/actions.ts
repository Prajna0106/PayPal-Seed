"use server";

import { isProblem, problemMessage, unwrap } from "@paypal-seed/api-client";
import { refresh } from "next/cache";
import { z } from "zod";
import { staffApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { REASON_CODES } from "./model";

export type ActionResult =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string; code?: string };

const decisionForm = z.object({
  caseId: z.uuid(),
  decision: z.enum(["APPROVE", "REJECT"], { message: "Choose approve or reject." }),
  reasonCode: z.enum(REASON_CODES.map((r) => r.code) as [string, ...string[]], { message: "Choose a reason." }),
  note: z.string().trim().max(500, "Keep the note under 500 characters.").optional(),
  evidenceViewedMs: z.coerce.number().int().min(0).max(86_400_000),
  key: z.uuid(),
});

/**
 * Records the maker's or the checker's decision. Which step it is follows from the approval request; the backend
 * refuses a second decision by the same officer (separation of duties) and a decision on a plan that changed.
 */
export async function decide(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = decisionForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form." };
  }
  const { caseId, decision, reasonCode, note, evidenceViewedMs, key } = parsed.data;
  if (reasonCode === "OTHER" && !note) {
    return { status: "error", message: "Explain an “Other” reason in the note." };
  }
  try {
    const api = await staffApi();
    const result = await unwrap(
      api.POST("/api/v1/desk/hardship/cases/{caseId}/decision", {
        params: { path: { caseId }, header: { "Idempotency-Key": key } },
        body: { decision, reasonCode, evidenceViewedMs, ...(note ? { note } : {}) },
      }),
    );
    refresh();
    if (result.requestStatus === "OPEN" && result.next === "CHECKER") {
      return { status: "ok", message: "Recorded as maker. A second, different officer now has to check it." };
    }
    if (result.requestStatus === "APPROVED") {
      return { status: "ok", message: "Approved. The new plan is being applied to the loan." };
    }
    if (result.requestStatus === "REJECTED") {
      return { status: "ok", message: "Rejected. The member will be told and can choose again." };
    }
    return { status: "ok", message: "Decision recorded." };
  } catch (error) {
    if (isProblem(error)) {
      if (error.code === "approval-stale" || error.code === "hardship-offer-stale") {
        refresh();
      }
      return { status: "error", message: problemMessage(error), code: error.code };
    }
    return { status: "error", message: "We could not reach the Seed backend. Try again in a moment." };
  }
}

const retryForm = z.object({ caseId: z.uuid() });

/** Re-runs applying an approved option after the saga gave up (for example a lending outage). */
export async function retryExecution(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = retryForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Unknown case." };
  }
  try {
    const api = await staffApi();
    await unwrap(api.POST("/api/v1/desk/hardship/cases/{caseId}/retry", { params: { path: { caseId: parsed.data.caseId } } }));
    refresh();
    return { status: "ok", message: "Retry started. The case updates when the plan is applied." };
  } catch (error) {
    return {
      status: "error",
      message: isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend.",
      ...(isProblem(error) ? { code: error.code } : {}),
    };
  }
}
