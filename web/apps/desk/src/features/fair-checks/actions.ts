"use server";

import { idempotent, isProblem, unwrap } from "@paypal-seed/api-client";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { refresh } from "next/cache";
import { describeProblem } from "@/features/applications/server/problems";
import { staffApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { MAX_TERM_MONTHS } from "./model";
import { decisionForm } from "./schema";
import { assessmentById } from "./server/queries";

export type DecisionResult =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string; code?: string };

/**
 * The officer's decision on a fair check. The contract states no limits for an adjustment, so the desk keeps one
 * itself, checked here against the stored check and not only in the form: an adjusted loan may be smaller than the
 * suggested one, never larger, and no shorter than the suggested term (a shorter term raises the instalment the check
 * sized as affordable).
 */
export async function recordDecision(_previous: DecisionResult, formData: FormData): Promise<DecisionResult> {
  await requireSession();
  const parsed = decisionForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form." };
  }
  const { assessmentId, key, decision, adjustedAmount, adjustedTermMonths, reason } = parsed.data;

  try {
    const card = await assessmentById(assessmentId);
    if (!card) {
      return { status: "error", message: "That fair check could not be found." };
    }
    if (card.officerDecision !== "PENDING") {
      refresh();
      return { status: "error", message: "An officer already decided this one. It has been reloaded." };
    }
    if (decision === "ADJUSTED") {
      if (adjustedAmount === undefined || adjustedTermMonths === undefined) {
        return { status: "error", message: "Enter the adjusted amount and term." };
      }
      if (adjustedAmount < 1 || adjustedAmount > card.suggestedLoanCents) {
        return {
          status: "error",
          message: `An adjusted loan can be at most ${formatMoney(card.suggestedLoanCents)}, what the check sized as affordable.`,
        };
      }
      if (adjustedTermMonths < card.suggestedTermMonths || adjustedTermMonths > MAX_TERM_MONTHS) {
        return {
          status: "error",
          message: `The term can be from ${card.suggestedTermMonths} to ${MAX_TERM_MONTHS} months.`,
        };
      }
    }

    const api = await staffApi();
    await unwrap(
      api.POST("/api/v1/desk/underwriting/assessments/{assessmentId}/decision", {
        params: { path: { assessmentId } },
        body: {
          decision,
          reason,
          ...(decision === "ADJUSTED" && adjustedAmount !== undefined && adjustedTermMonths !== undefined
            ? { adjustedLoanCents: adjustedAmount, adjustedTermMonths }
            : {}),
        },
        ...idempotent(key),
      }),
    );
    refresh();
    return {
      status: "ok",
      message:
        decision === "APPROVED"
          ? "Approved as suggested."
          : decision === "ADJUSTED"
            ? "Adjusted and saved."
            : "Declined. The reason is saved with the check.",
    };
  } catch (error) {
    if (isProblem(error)) {
      if (error.status === 409) {
        refresh();
      }
      return { status: "error", message: describeProblem(error), code: error.code };
    }
    return { status: "error", message: "We could not reach the Seed backend. Try again in a moment." };
  }
}
