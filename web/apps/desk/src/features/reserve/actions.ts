"use server";

import { isProblem, unwrap } from "@paypal-seed/api-client";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { refresh } from "next/cache";
import { z } from "zod";
import { reserveErrorMessage } from "./model";
import { dollarsToCents, decideSchema, raiseSchema } from "./schema";
import { newIdempotencyKey } from "./server/keys";
import { staffApi } from "@/server/api";
import { hasRole, requireSession } from "@/server/session";

export type ActionResult =
  | { status: "idle" }
  | { status: "ok"; message: string; nextKey: string }
  | { status: "error"; message: string; nextKey: string };

/** The key the form sent, handed back so the form keeps using it until an action succeeds or conflicts. */
function submittedKey(formData: FormData): string {
  const key = formData.get("key");
  return typeof key === "string" && z.uuid().safeParse(key).success ? key : newIdempotencyKey();
}

/** A conflict means the screen was stale or the key was used for other details: re-read, and use a fresh key. */
function failure(error: unknown, key: string): ActionResult {
  const conflict = isProblem(error) && error.status === 409;
  if (conflict) {
    refresh();
  }
  return { status: "error", message: reserveErrorMessage(error), nextKey: conflict ? newIdempotencyKey() : key };
}

/** Maker step: an officer asks for the fund to pay part of a member's loan. The fund never pays the member. */
export async function raiseCoverRequest(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  const session = await requireSession();
  const submitted = submittedKey(formData);
  if (!hasRole(session, "OFFICER") && !hasRole(session, "ADMIN")) {
    return { status: "error", message: "Only an officer can raise a cover request. Switch persona (bottom left).", nextKey: submitted };
  }
  const parsed = raiseSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form.", nextKey: submitted };
  }
  const { memberId, loanId, amount, reasonCode, hardshipCaseId, note, key } = parsed.data;
  const amountCents = dollarsToCents(amount);
  if (amountCents === null) {
    return { status: "error", message: "Enter an amount in dollars, for example 50 or 49.50.", nextKey: submitted };
  }
  if (reasonCode === "OTHER" && !note) {
    return { status: "error", message: "Explain an “Other” reason in the note.", nextKey: submitted };
  }
  try {
    const api = await staffApi();
    await unwrap(
      api.POST("/api/v1/desk/reserve/cover-requests", {
        params: { header: { "Idempotency-Key": key } },
        body: { memberId, loanId, amountCents, reasonCode, ...(hardshipCaseId ? { hardshipCaseId } : {}), ...(note ? { note } : {}) },
      }),
    );
    refresh();
    return {
      status: "ok",
      message: `Cover request for ${formatMoney(amountCents)} raised. A different officer with the checker role now has to decide it.`,
      nextKey: newIdempotencyKey(),
    };
  } catch (error) {
    return failure(error, submitted);
  }
}

/**
 * Records the maker's or the checker's decision. Which step it is follows from the approval request; the backend
 * refuses a second decision by the same officer and an approval the fund can no longer pay.
 */
export async function decideCoverRequest(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const submitted = submittedKey(formData);
  const parsed = decideSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form.", nextKey: submitted };
  }
  const { coverRequestId, decision, reasonCode, note, evidenceViewedMs, key } = parsed.data;
  if (reasonCode === "OTHER" && !note) {
    return { status: "error", message: "Explain an “Other” reason in the note.", nextKey: submitted };
  }
  try {
    const api = await staffApi();
    const result = await unwrap(
      api.POST("/api/v1/desk/reserve/cover-requests/{coverRequestId}/decision", {
        params: { path: { coverRequestId }, header: { "Idempotency-Key": key } },
        body: { decision, reasonCode, evidenceViewedMs, ...(note ? { note } : {}) },
      }),
    );
    refresh();
    const nextKey = newIdempotencyKey();
    const signed = result.role === "MAKER" ? "maker" : "checker";
    if (result.requestStatus === "OPEN" && result.next === "CHECKER") {
      return { status: "ok", message: `Signed as ${signed}. A second, different officer with the checker role has to sign too.`, nextKey };
    }
    if (result.coverStatus === "APPROVED") {
      return { status: "ok", message: `Signed as ${signed}. Approved: the fund now pays the loan, not the member.`, nextKey };
    }
    if (result.coverStatus === "FAILED") {
      return { status: "error", message: "Both officers approved, but the fund or the loan no longer allowed the payment. Nothing was paid.", nextKey };
    }
    if (result.coverStatus === "REJECTED") {
      return { status: "ok", message: `Signed as ${signed}. Rejected: nothing is paid from the fund.`, nextKey };
    }
    return { status: "ok", message: `Decision recorded as ${signed}.`, nextKey };
  } catch (error) {
    return failure(error, submitted);
  }
}
