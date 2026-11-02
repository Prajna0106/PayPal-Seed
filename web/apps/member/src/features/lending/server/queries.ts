import "server-only";

import { type Schemas, unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { formatRelative } from "@paypal-seed/ui/lib/format";
import { unstable_rethrow } from "next/navigation";
import { memberApi } from "@/server/api";
import type { ApplicationStatus, Eligibility, FairCheckCard, IncomeConsent, LoanPreview } from "../model";

type Application = Schemas["LoanApplication"];

/** Optional extras (a suggestion, a preference): a failure here must not stop the page. */
async function soft<T>(pending: Promise<T | null>): Promise<T | null> {
  try {
    return await pending;
  } catch (error) {
    unstable_rethrow(error);
    return null;
  }
}

export async function getEligibility(): Promise<Eligibility> {
  const api = await memberApi();
  return unwrap(api.GET("/api/v1/loans/eligibility", {}));
}

/**
 * The payment and the dated schedule for a loan, worked out by the backend with the same code as the real disbursement.
 * Cents in, because the caller already holds cents (the fair check) or has converted whole dollars once.
 */
export async function getLoanPreview(amountCents: number, termMonths: number, dueDay: number | null): Promise<LoanPreview> {
  const api = await memberApi();
  return unwrap(
    api.POST("/api/v1/loans/applications/preview", {
      body: { requestedAmountCents: amountCents, termMonths, ...(dueDay !== null ? { preferredDueDay: dueDay } : {}) },
    }),
  );
}

export interface ApplyContext {
  eligibility: Eligibility;
  consent: IncomeConsent | null;
  /** The due day the backend suggests (the day after money usually arrives), if it has one. */
  suggestedDueDay: number | null;
  walletPreference: "PAYPAL" | "VENMO" | null;
}

/** Everything the early steps need, read in parallel. */
export async function getApplyContext(): Promise<ApplyContext> {
  const api = await memberApi();
  const [eligibility, consent, dueDay, profile] = await Promise.all([
    unwrap(api.GET("/api/v1/loans/eligibility", {})),
    soft(unwrapOrNull(api.GET("/api/v1/underwriting/consent", {}))),
    soft(unwrapOrNull(api.GET("/api/v1/members/me/due-day-suggestion", {}))),
    soft(unwrapOrNull(api.GET("/api/v1/members/me"))),
  ]);
  return {
    eligibility,
    consent,
    suggestedDueDay: dueDay?.dueDay ?? null,
    walletPreference: profile?.walletPreference ?? null,
  };
}

export async function getIncomeConsent(): Promise<IncomeConsent | null> {
  const api = await memberApi();
  return soft(unwrapOrNull(api.GET("/api/v1/underwriting/consent", {})));
}

/** One fair check, or `null` when it is not this member's (the backend answers 404 for another member's). */
export async function getAssessment(assessmentId: string): Promise<FairCheckCard | null> {
  const api = await memberApi();
  return unwrapOrNull(api.GET("/api/v1/underwriting/assessments/{assessmentId}", { params: { path: { assessmentId } } }));
}

export interface ReviewContext {
  card: FairCheckCard;
  consent: IncomeConsent | null;
  walletPreference: "PAYPAL" | "VENMO" | null;
}

export async function getReviewContext(assessmentId: string): Promise<ReviewContext | null> {
  const api = await memberApi();
  const [card, consent, profile] = await Promise.all([
    getAssessment(assessmentId),
    soft(unwrapOrNull(api.GET("/api/v1/underwriting/consent", {}))),
    soft(unwrapOrNull(api.GET("/api/v1/members/me"))),
  ]);
  return card ? { card, consent, walletPreference: profile?.walletPreference ?? null } : null;
}

export interface ApplicationModel {
  id: string;
  purpose: string;
  amountCents: number;
  termMonths: number;
  /** The monthly payment the backend sized for this application. */
  monthlyCents: number | null;
  dueDay: number | null;
  status: ApplicationStatus;
  createdAt: string;
  loanId: string | null;
  rejectionNote: string | null;
  /** A payout PayPal did not complete. The member sees a calm sentence, never the code. */
  payoutTrouble: boolean;
  coolingOff: { expiresAt: string; open: boolean; relative: string } | null;
}

function toApplication(a: Application, now: Date): ApplicationModel {
  const expiresAt = a.coolingOffExpiresAt ?? null;
  return {
    id: a.id,
    purpose: a.purpose,
    amountCents: a.requestedAmountCents,
    termMonths: a.termMonths,
    monthlyCents: a.monthlyInstalmentCents ?? null,
    dueDay: a.preferredDueDay ?? null,
    status: a.status,
    createdAt: a.createdAt,
    loanId: a.loanId ?? null,
    rejectionNote: a.rejectionReason?.trim() || null,
    payoutTrouble: Boolean(a.payoutIssue),
    coolingOff:
      expiresAt && a.status === "DISBURSED"
        ? { expiresAt, open: new Date(expiresAt).getTime() > now.getTime(), relative: formatRelative(expiresAt, now) }
        : null,
  };
}

export async function listApplications(): Promise<ApplicationModel[]> {
  const api = await memberApi();
  const now = new Date();
  const rows = await unwrap(api.GET("/api/v1/loans/applications", {}));
  return rows.map((row) => toApplication(row, now)).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

/** `null` when the application is missing or belongs to someone else (both are 404). */
export async function getApplication(applicationId: string): Promise<ApplicationModel | null> {
  const api = await memberApi();
  const row = await unwrapOrNull(api.GET("/api/v1/loans/applications/{applicationId}", { params: { path: { applicationId } } }));
  return row ? toApplication(row, new Date()) : null;
}
