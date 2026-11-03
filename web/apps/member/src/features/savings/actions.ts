"use server";

import { idempotent, isProblem, unwrap } from "@paypal-seed/api-client";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { createHash, randomUUID } from "node:crypto";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import { redirect, unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";
import { dollarsToCents, type Goal, safeApprovalUrl, toGoal } from "./model";
import { captureSchema, changeAmountSchema, coachSchema, createGoalSchema, fieldErrors, goalIdSchema, topUpSchema, withdrawSchema } from "./schema";
import { isMissingRecipient, savingsMessage } from "./server/messages";

/**
 * One Idempotency-Key per user action, made here on the server. The form carries a key made when it was rendered; the
 * body is hashed into it, so the same body sent again (a retry after a timeout) is the same request and a changed body
 * is a new one instead of a `409`.
 */
function keyFor(attemptKey: string, body: unknown): string {
  return `${attemptKey}-${createHash("sha256").update(JSON.stringify(body)).digest("hex").slice(0, 16)}`;
}

export type CreateGoalState = { status: "idle" } | { status: "error"; message: string | null; fields: Record<string, string> };

/**
 * Starts a goal, then sends the member to PayPal to agree to the monthly plan. Nothing is saved until PayPal confirms
 * (the goal waits as `PENDING_APPROVAL`). The target is optional in the form; the backend needs one, so an empty
 * target becomes twelve months of saving.
 */
export async function createGoal(_previous: CreateGoalState, formData: FormData): Promise<CreateGoalState> {
  const parsed = createGoalSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: null, fields: fieldErrors(parsed.error) };
  }
  const { name, monthly, target, wallet, attemptKey } = parsed.data;
  const body = {
    name,
    monthlyAmount: monthly / 100,
    targetAmount: (target ?? monthly * 12) / 100,
    currency: "USD",
    wallet,
  };

  let goal: Goal;
  try {
    const api = await memberApi();
    goal = toGoal(
      await unwrap(api.POST("/api/v1/savings/goals", { params: { header: { "Idempotency-Key": keyFor(attemptKey, body) } }, body })),
    );
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] create goal failed", error);
    return { status: "error", message: savingsMessage(error, "create"), fields: {} };
  }

  revalidatePath("/app/savings");
  if (goal.status === "PENDING_APPROVAL") {
    // The member agrees at PayPal; if there is no safe link the goal page shows how to finish.
    redirect((safeApprovalUrl(goal.approvalUrl) ?? `/app/savings/${goal.id}`) as Route);
  }
  redirect(`/app/savings/${goal.id}?welcome=1` as Route);
}

export type CoachResult =
  | {
      ok: true;
      suggestedCents: number;
      unlockedLoanCents: number | null;
      streakMonths: number;
      instalmentCents: number | null;
      termMonths: number;
      /** Written by the assistant when `modelGenerated`, otherwise a fixed template. Never the source of a figure. */
      message: string;
      modelGenerated: boolean;
    }
  | { ok: false; message: string };

/**
 * Asks the savings coach for a plan from what the member says they earn and spend. The figures come back as fields
 * computed by code; the sentence is the only model-written part and is labelled where it is shown.
 */
export async function suggestPlan(input: {
  goalName: string;
  income: string;
  expenses: string;
  targetCents: number | null;
  language: "en" | "es";
}): Promise<CoachResult> {
  const parsed = coachSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: Object.values(fieldErrors(parsed.error))[0] ?? "Please check what you entered." };
  }
  const { goalName, income, expenses, targetCents, language } = parsed.data;
  try {
    const api = await memberApi();
    const plan = await unwrap(
      api.POST("/api/v1/savings/coach/suggest", {
        body: {
          goalName: goalName || undefined,
          statedMonthlyIncome: income / 100,
          ...(expenses !== null ? { statedMonthlyExpenses: expenses / 100 } : {}),
          ...(targetCents ? { targetAmount: targetCents / 100 } : {}),
          currency: "USD",
        },
      }),
    );
    return {
      ok: true,
      suggestedCents: dollarsToCents(plan.suggestedMonthlySaving),
      unlockedLoanCents: plan.unlockedLoanAmount === undefined ? null : dollarsToCents(plan.unlockedLoanAmount),
      streakMonths: plan.streakMonthsToUnlock,
      instalmentCents: plan.suggestedMonthlyInstalment === undefined ? null : dollarsToCents(plan.suggestedMonthlyInstalment),
      termMonths: plan.suggestedLoanTermMonths,
      message: language === "es" ? plan.messageEs : plan.messageEn,
      modelGenerated: plan.modelGenerated,
    };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] coach suggestion failed", error);
    return { ok: false, message: "The coach could not make a suggestion right now. You can still pick your own amount." };
  }
}

export type TopUpOrderResult = { ok: true; orderId: string } | { ok: false; message: string };
export type TopUpCaptureResult = { ok: true } | { ok: false; message: string };

/** Creates the one-off order on the backend; the PayPal buttons collect the member's approval (PayPal or Venmo). */
export async function startTopUp(input: { goalId: string; amount: string }): Promise<TopUpOrderResult> {
  const parsed = topUpSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: Object.values(fieldErrors(parsed.error))[0] ?? "Enter an amount in dollars, like 20." };
  }
  const { goalId, amount } = parsed.data;
  try {
    const api = await memberApi();
    const order = await unwrap(
      api.POST("/api/v1/savings/goals/{goalId}/topup", {
        params: { path: { goalId }, header: { "Idempotency-Key": randomUUID() } },
        body: { amount: amount / 100, currency: "USD" },
      }),
    );
    return { ok: true, orderId: order.orderId };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] start top-up failed", error);
    return { ok: false, message: savingsMessage(error, "topup") };
  }
}

/** Captures the approved order. The goal is credited when PayPal confirms, so the page may show it a moment later. */
export async function captureTopUp(input: { goalId: string; orderId: string }): Promise<TopUpCaptureResult> {
  const parsed = captureSchema.safeParse(input);
  if (!parsed.success) {
    return { ok: false, message: "That top-up is not available." };
  }
  const { goalId, orderId } = parsed.data;
  try {
    const api = await memberApi();
    await unwrap(api.POST("/api/v1/savings/goals/{goalId}/topup/{orderId}/capture", { params: { path: { goalId, orderId } } }));
    revalidatePath(`/app/savings/${goalId}`);
    return { ok: true };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] capture top-up failed", error);
    return { ok: false, message: "PayPal could not finish the top-up. If money left your account, it will show here soon." };
  }
}

/**
 * Asks the backend to read the goal's subscription and payments from PayPal and apply what it finds, the same way a
 * webhook would. It is a safe re-read (the backend rate-limits it per goal), and it is what moves a goal on when PayPal
 * cannot reach the backend with a webhook. A failure is quiet: the page keeps its stored state and tries again.
 */
export async function syncGoal(goalId: string): Promise<void> {
  const parsed = goalIdSchema.safeParse({ goalId });
  if (!parsed.success) {
    return;
  }
  try {
    const api = await memberApi();
    await unwrap(api.POST("/api/v1/savings/goals/{goalId}/sync", { params: { path: { goalId: parsed.data.goalId } } }));
    revalidatePath(`/app/savings/${parsed.data.goalId}`);
    revalidatePath("/app/savings");
  } catch (error) {
    unstable_rethrow(error);
    console.warn("[savings] sync failed", error instanceof Error ? error.name : "unknown");
  }
}

export type ToggleState = { status: "idle" } | { status: "done" } | { status: "error"; message: string };

/** Pauses saving. The backend suspends the PayPal plan; money already saved stays. */
export async function pauseGoal(_previous: ToggleState, formData: FormData): Promise<ToggleState> {
  const parsed = goalIdSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "That goal is not available." };
  }
  const { goalId } = parsed.data;
  try {
    const api = await memberApi();
    await unwrap(api.POST("/api/v1/savings/goals/{goalId}/pause", { params: { path: { goalId } } }));
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] pause failed", error);
    return { status: "error", message: savingsMessage(error, "pause") };
  }
  revalidatePath(`/app/savings/${goalId}`);
  revalidatePath("/app/savings");
  return { status: "done" };
}

/** Resumes saving. If PayPal ended the old plan, the goal comes back as pending and the member agrees again at PayPal. */
export async function resumeGoal(_previous: ToggleState, formData: FormData): Promise<ToggleState> {
  const parsed = goalIdSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "That goal is not available." };
  }
  const { goalId } = parsed.data;
  let approval: string | null = null;
  try {
    const api = await memberApi();
    const goal = toGoal(await unwrap(api.POST("/api/v1/savings/goals/{goalId}/resume", { params: { path: { goalId } } })));
    approval = goal.status === "PENDING_APPROVAL" ? safeApprovalUrl(goal.approvalUrl) : null;
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] resume failed", error);
    return { status: "error", message: savingsMessage(error, "resume") };
  }
  revalidatePath(`/app/savings/${goalId}`);
  revalidatePath("/app/savings");
  if (approval) {
    redirect(approval as Route);
  }
  return { status: "done" };
}

export type WithdrawState =
  | { status: "idle" }
  | { status: "sent"; amountCents: number }
  | { status: "error"; message: string; needsSignIn: boolean; fields: Record<string, string> };

/** Takes money out through a PayPal payout. The amount is checked against the goal's free balance read here. */
export async function withdraw(_previous: WithdrawState, formData: FormData): Promise<WithdrawState> {
  const parsed = withdrawSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "", needsSignIn: false, fields: fieldErrors(parsed.error) };
  }
  const { goalId, amount, attemptKey } = parsed.data;
  const body = { amount: amount / 100, currency: "USD" };
  try {
    const api = await memberApi();
    await unwrap(
      api.POST("/api/v1/savings/goals/{goalId}/withdraw", {
        params: { path: { goalId }, header: { "Idempotency-Key": keyFor(attemptKey, body) } },
        body,
      }),
    );
    revalidatePath(`/app/savings/${goalId}`);
    revalidatePath("/app/savings");
    return { status: "sent", amountCents: amount };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] withdraw failed", error);
    // A refusal for "too much" has no code of its own, so read the goal and say how much is free (after the call, so a
    // retry of a payout already reserved is not mistaken for an overdraw).
    const free = isProblem(error) && (error.status === 400 || error.status === 409) ? await freeBalance(goalId) : null;
    if (free !== null && amount > free) {
      return { status: "error", message: "", needsSignIn: false, fields: { amount: `You can take out up to ${formatMoney(free)} right now.` } };
    }
    return { status: "error", message: savingsMessage(error, "withdraw"), needsSignIn: isMissingRecipient(error), fields: {} };
  }
}

async function freeBalance(goalId: string): Promise<number | null> {
  try {
    const api = await memberApi();
    return toGoal(await unwrap(api.GET("/api/v1/savings/goals/{goalId}", { params: { path: { goalId } } }))).savedCents;
  } catch (error) {
    unstable_rethrow(error);
    return null;
  }
}

export type ChangeAmountState =
  | { status: "idle" }
  | { status: "done"; monthlyCents: number }
  | { status: "error"; message: string; fields: Record<string, string> };

/**
 * Changes the monthly amount. PayPal may need the member to agree to the new amount: the goal then carries an approval
 * link and the member goes there, as after resuming. A refusal because the goal is not in a state to change (409) or
 * the amount is not whole dollars (422) is said in plain words.
 */
export async function changeMonthlyAmount(_previous: ChangeAmountState, formData: FormData): Promise<ChangeAmountState> {
  const parsed = changeAmountSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { status: "error", message: "", fields: fieldErrors(parsed.error) };
  }
  const { goalId, monthly, attemptKey } = parsed.data;
  // Savings routes speak decimal dollars; the amount is whole cents until this one conversion.
  const body = { monthlyAmount: monthly / 100, currency: "USD" };
  let approval: string | null = null;
  try {
    const api = await memberApi();
    const goal = toGoal(
      await unwrap(
        api.POST("/api/v1/savings/goals/{goalId}/amount", {
          ...idempotent(keyFor(attemptKey, body)),
          params: { path: { goalId } },
          body,
        }),
      ),
    );
    approval = safeApprovalUrl(goal.approvalUrl);
  } catch (error) {
    unstable_rethrow(error);
    console.error("[savings] change amount failed", error);
    return { status: "error", message: savingsMessage(error, "amount"), fields: {} };
  }
  revalidatePath(`/app/savings/${goalId}`);
  revalidatePath("/app/savings");
  if (approval) {
    redirect(approval as Route);
  }
  return { status: "done", monthlyCents: monthly };
}
