"use server";

import { ApiProblem, idempotent, unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import type { Route } from "next";
import { revalidatePath } from "next/cache";
import { redirect, unstable_rethrow } from "next/navigation";
import { applyHref, type LoanPreview, type WizardParams } from "./model";
import { cancelSchema, disputeSchema, loanChoiceSchema, ownNumbersSchema, previewSchema, submitSchema } from "./schema";
import { friendlyProblem } from "./server/problems";
import { getAssessment, getEligibility, getIncomeConsent, getLoanPreview, listApplications } from "./server/queries";
import { memberApi } from "@/server/api";

export type FormState =
  | { status: "idle" }
  | { status: "ok"; message: string }
  | { status: "error"; message: string; relink?: boolean };

function fail(message: string, relink?: boolean): FormState {
  return { status: "error", message, ...(relink ? { relink } : {}) };
}

function firstIssue(error: { issues: { message: string }[] }): string {
  return error.issues[0]?.message ?? "Please check what you entered.";
}

export type PreviewState = { status: "ok"; preview: LoanPreview } | { status: "error"; message: string };

/**
 * The monthly payment while the member moves the amount or the months. A Server Action, so the browser never calls the
 * backend, and the payment is the backend's own answer (same code as the real loan), never worked out in the browser.
 * Nothing is saved. Whole dollars become cents here, once.
 */
export async function previewLoan(amount: number, term: number, dueDay: number | null): Promise<PreviewState> {
  const parsed = previewSchema.safeParse({ amount, term, dueDay });
  if (!parsed.success) {
    return { status: "error", message: "Choose an amount and a number of months to see your payment." };
  }
  try {
    const preview = await getLoanPreview(parsed.data.amount * 100, parsed.data.term, parsed.data.dueDay);
    return { status: "ok", preview };
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] preview failed", error);
    return { status: "error", message: friendlyProblem(error, "We could not work out your payment just now.").message };
  }
}

/** Reads the amount step's fields from a form and keeps them within what the backend says is allowed. */
type Choice = Required<Pick<WizardParams, "amount" | "term" | "purpose">> & Pick<WizardParams, "dueDay">;

async function readChoice(formData: FormData): Promise<{ ok: true; choice: Choice } | { ok: false; state: FormState }> {
  const parsed = loanChoiceSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return { ok: false, state: fail(firstIssue(parsed.error)) };
  }
  const eligibility = await getEligibility();
  const { amount, term } = parsed.data;
  if (!eligibility.eligible || amount * 100 > eligibility.maxLoanCents || term < eligibility.minTermMonths || term > eligibility.maxTermMonths) {
    return { ok: false, state: fail("Please go back and choose an amount and term within your limit.") };
  }
  return { ok: true, choice: { amount, term, purpose: parsed.data.purpose, dueDay: parsed.data.dueDay } };
}

/**
 * The member says yes to reading their PayPal income. The record is written in both places ADR 0012 and 0018 name: the
 * member's consent ledger (identity) and the consent the fair check itself enforces (underwriting). Then the check runs.
 * Every write is idempotent, so a retry after a PayPal hiccup repeats nothing.
 */
export async function allowAndRunCheck(_previous: FormState, formData: FormData): Promise<FormState> {
  const choice = await readChoice(formData);
  if (!choice.ok) {
    return choice.state;
  }
  const { amount, term, purpose, dueDay } = choice.choice;
  const api = await memberApi();
  let assessmentId: string;
  try {
    try {
      await unwrap(api.POST("/api/v1/members/me/consents", { body: { type: "INCOME_READ" } }));
    } catch (error) {
      unstable_rethrow(error);
      // A demo member with no PayPal sign-in has no identity record; the fair check's own consent is what counts.
      if (!(error instanceof ApiProblem && error.code === "member-not-found")) {
        throw error;
      }
    }
    await unwrap(api.POST("/api/v1/underwriting/consent", {}));
    // Whole dollars typed by the member become cents here, once.
    const card = await unwrap(
      api.POST("/api/v1/underwriting/assessments", {
        body: { purpose, requestedAmountCents: amount * 100, requestedTermMonths: term },
      }),
    );
    assessmentId = card.assessmentId;
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] consent or fair check failed", error);
    const problem = friendlyProblem(error, "We could not run the fair check just now. Please try again.");
    return fail(problem.message, problem.relink);
  }
  redirect(applyHref("check", { amount, term, purpose, dueDay, assessment: assessmentId }));
}

/**
 * The member would rather not share their income. If they had said yes before, that is withdrawn first (both
 * records), then the check runs on the numbers they type. The application can still go ahead.
 */
export async function runCheckWithOwnNumbers(_previous: FormState, formData: FormData): Promise<FormState> {
  const choice = await readChoice(formData);
  if (!choice.ok) {
    return choice.state;
  }
  const numbers = ownNumbersSchema.safeParse(Object.fromEntries(formData));
  if (!numbers.success) {
    return fail(firstIssue(numbers.error));
  }
  const { amount, term, purpose, dueDay } = choice.choice;
  const api = await memberApi();
  let assessmentId: string;
  try {
    const consent = await getIncomeConsent();
    if (consent?.consented) {
      try {
        await unwrap(api.DELETE("/api/v1/members/me/consents/{type}", { params: { path: { type: "INCOME_READ" } } }));
      } catch (error) {
        unstable_rethrow(error);
        if (!(error instanceof ApiProblem && error.code === "member-not-found")) {
          throw error;
        }
      }
      await unwrap(api.DELETE("/api/v1/underwriting/consent", {}));
    }
    // Typed dollars become cents here, once.
    const card = await unwrap(
      api.POST("/api/v1/underwriting/assessments", {
        body: {
          purpose,
          requestedAmountCents: amount * 100,
          requestedTermMonths: term,
          statedMonthlyIncomeCents: Math.round(numbers.data.statedIncome * 100),
          ...(numbers.data.statedExpenses !== undefined
            ? { statedMonthlyExpensesCents: Math.round(numbers.data.statedExpenses * 100) }
            : {}),
        },
      }),
    );
    assessmentId = card.assessmentId;
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] fair check with own numbers failed", error);
    const problem = friendlyProblem(error, "We could not run the fair check just now. Please try again.");
    return fail(problem.message, problem.relink);
  }
  redirect(applyHref("check", { amount, term, purpose, dueDay, assessment: assessmentId }));
}

/** The member says a number in their fair check is wrong. A person looks at it; nothing is decided by this. */
export async function disputeCheck(_previous: FormState, formData: FormData): Promise<FormState> {
  const parsed = disputeSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return fail(firstIssue(parsed.error));
  }
  const api = await memberApi();
  try {
    await unwrap(
      api.POST("/api/v1/underwriting/assessments/{assessmentId}/dispute", {
        params: { path: { assessmentId: parsed.data.assessmentId } },
        body: { note: parsed.data.note },
      }),
    );
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] dispute failed", error);
    return fail(friendlyProblem(error, "We could not send that just now. Please try again.").message);
  }
  revalidatePath("/app/apply");
  return { status: "ok", message: "Thank you. A person at Seed will look at this. Nothing changes until they have." };
}

/**
 * Sends the application. The amount, term and purpose come from the fair check on the backend, never from the page,
 * so what the member reviewed is what is sent. One key per rendered review page; the backend also answers a repeat
 * of the same application with the one that exists, and so does the check below for a double tap.
 */
export async function submitApplication(_previous: FormState, formData: FormData): Promise<FormState> {
  const parsed = submitSchema.safeParse(Object.fromEntries(formData));
  if (!parsed.success) {
    return fail("Something was missing. Please go back to the review step and try again.");
  }
  const { assessmentId, attemptKey, dueDay } = parsed.data;
  const api = await memberApi();
  let applicationId: string;
  try {
    const [card, consent, existing] = await Promise.all([getAssessment(assessmentId), getIncomeConsent(), listApplications()]);
    if (!card || !card.recommended || card.suggestedLoanCents <= 0) {
      return fail("This fair check does not support a loan yet. Please go back and choose a different amount.");
    }
    const twin = existing.find(
      (a) =>
        a.amountCents === card.suggestedLoanCents &&
        a.termMonths === card.suggestedTermMonths &&
        (a.status === "SUBMITTED" || a.status === "PENDING_SECOND_APPROVAL" || a.status === "APPROVED") &&
        Date.now() - new Date(a.createdAt).getTime() < 10 * 60_000,
    );
    if (twin) {
      applicationId = twin.id;
    } else {
      const profile = await unwrapOrNull(api.GET("/api/v1/members/me"));
      const wallet = profile?.walletPreference;
      const created = await unwrap(
        api.POST("/api/v1/loans/applications", {
          ...idempotent(attemptKey),
          body: {
            purpose: card.purpose,
            requestedAmountCents: card.suggestedLoanCents,
            termMonths: card.suggestedTermMonths,
            fairCheckConsented: consent?.consented ?? false,
            ...(dueDay !== undefined ? { preferredDueDay: dueDay } : {}),
            ...(wallet ? { payoutWallet: wallet } : {}),
          },
        }),
      );
      applicationId = created.id;
    }
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] submit failed", error);
    return fail(friendlyProblem(error, "We could not send your application just now. Nothing was saved. Please try again.").message);
  }
  revalidatePath("/app/applications");
  revalidatePath("/app");
  redirect(`/app/apply/submitted?application=${applicationId}` as Route);
}

/** Returns a loan inside the 48-hour window. Refused (409) once the money is still on its way or the window ended. */
export async function cancelApplication(applicationId: string): Promise<FormState> {
  const parsed = cancelSchema.safeParse({ applicationId });
  if (!parsed.success) {
    return fail("We could not find that application.");
  }
  const api = await memberApi();
  try {
    await unwrap(
      api.POST("/api/v1/loans/applications/{applicationId}/cooling-off-cancel", {
        params: { path: { applicationId: parsed.data.applicationId } },
      }),
    );
  } catch (error) {
    unstable_rethrow(error);
    console.error("[applications] cancel failed", error);
    return fail(friendlyProblem(error, "We could not return the loan just now. Please try again.").message);
  }
  revalidatePath(`/app/applications/${parsed.data.applicationId}`);
  revalidatePath("/app/applications");
  revalidatePath("/app/loans");
  revalidatePath("/app");
  return { status: "ok", message: "Done. The loan is returned and nothing is owed." };
}
