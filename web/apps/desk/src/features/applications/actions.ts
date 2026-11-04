"use server";

import { idempotent, isProblem, unwrap } from "@paypal-seed/api-client";
import { refresh } from "next/cache";
import { staffApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { explainPayoutIssue } from "./model";
import { approveForm, disburseForm, rejectForm, syncPayoutInput } from "./schema";
import { describeProblem, isStale } from "./server/problems";

export type ActionResult =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string; code?: string };

const OFFLINE: ActionResult = {
  status: "error",
  message: "We could not reach the Seed backend. Try again in a moment.",
};

function failure(error: unknown): ActionResult {
  if (isProblem(error)) {
    if (isStale(error)) {
      // The application moved on without this officer: show them where it stands now.
      refresh();
    }
    return { status: "error", message: describeProblem(error), code: error.code };
  }
  return OFFLINE;
}

/**
 * First or second approval. Which one it is follows from the application; the backend refuses a second approval by
 * the same officer (separation of duties), and the form says so before the click when it already knows.
 */
export async function approveApplication(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = approveForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Reload the page and try again." };
  }
  const { applicationId, key } = parsed.data;
  try {
    const api = await staffApi();
    const application = await unwrap(
      api.POST("/api/v1/desk/loans/applications/{applicationId}/approve", {
        params: { path: { applicationId } },
        ...idempotent(key),
      }),
    );
    refresh();
    return {
      status: "ok",
      message:
        application.status === "PENDING_SECOND_APPROVAL"
          ? "Your approval is recorded. A different officer must sign this one next."
          : "Approved by two officers. It can now be paid out.",
    };
  } catch (error) {
    return failure(error);
  }
}

export async function rejectApplication(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = rejectForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: parsed.error.issues[0]?.message ?? "Check the form." };
  }
  const { applicationId, key, reason } = parsed.data;
  try {
    const api = await staffApi();
    await unwrap(
      api.POST("/api/v1/desk/loans/applications/{applicationId}/reject", {
        params: { path: { applicationId } },
        body: { reason },
        ...idempotent(key),
      }),
    );
    refresh();
    return { status: "ok", message: "Rejected. The reason is saved on the application." };
  } catch (error) {
    return failure(error);
  }
}

/**
 * Starts (or, after a refused payout, restarts) the PayPal payout. The loan exists only once PayPal confirms, so a
 * `DISBURSING` answer is normal: the page then refreshes itself until the payout settles.
 */
export async function disburseApplication(_previous: ActionResult, formData: FormData): Promise<ActionResult> {
  await requireSession();
  const parsed = disburseForm.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "Reload the page and try again." };
  }
  const { applicationId, key } = parsed.data;
  try {
    const api = await staffApi();
    const application = await unwrap(
      api.POST("/api/v1/desk/loans/applications/{applicationId}/disburse", {
        params: { path: { applicationId } },
        ...idempotent(key),
      }),
    );
    refresh();
    if (application.status === "DISBURSED") {
      return { status: "ok", message: "Paid out. The loan and its schedule now exist." };
    }
    if (application.status === "DISBURSING") {
      return { status: "ok", message: "Payout started. Waiting for PayPal to confirm." };
    }
    if (application.payoutIssue) {
      return { status: "error", message: explainPayoutIssue(application.payoutIssue), code: "payout-refused" };
    }
    return { status: "ok", message: "Payout requested." };
  } catch (error) {
    return failure(error);
  }
}

/**
 * "Check again": asks the backend to read the application's payout from PayPal, for a webhook that never came (a local
 * machine PayPal cannot call, or a lost delivery). It is a safe re-read, so it carries no idempotency key, and the page
 * refreshes with whatever the backend then holds. Called by the payout panel's own timer and by its button.
 */
export async function syncApplicationPayout(applicationId: string): Promise<ActionResult> {
  await requireSession();
  const parsed = syncPayoutInput.safeParse(applicationId);
  if (!parsed.success) {
    return { status: "error", message: "Reload the page and try again." };
  }
  try {
    const api = await staffApi();
    const application = await unwrap(
      api.POST("/api/v1/desk/applications/{applicationId}/payout/sync", {
        params: { path: { applicationId: parsed.data } },
      }),
    );
    refresh();
    if (application.status === "DISBURSED") {
      return { status: "ok", message: "PayPal confirmed the payout. The loan and its schedule now exist." };
    }
    if (application.status === "DISBURSING") {
      return { status: "ok", message: "PayPal has not finished the payout yet." };
    }
    if (application.payoutIssue) {
      return { status: "error", message: explainPayoutIssue(application.payoutIssue), code: "payout-refused" };
    }
    return { status: "ok", message: "Checked with PayPal." };
  } catch (error) {
    return failure(error);
  }
}
