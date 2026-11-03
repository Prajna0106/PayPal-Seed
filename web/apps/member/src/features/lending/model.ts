import type { Schemas } from "@paypal-seed/api-client";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import type { Route } from "next";

/** Contract shapes the borrowing screens read. Nothing here is hand-written: the types come from the OpenAPI file. */
export type Eligibility = Schemas["Eligibility"];
export type FairCheckCard = Schemas["FairCheckCard"];
export type IncomeConsent = Schemas["ConsentStatus"];
export type ApplicationStatus = Schemas["ApplicationStatus"];
export type RecordLevel = Schemas["RecordLevel"];
/** What a loan would look like today, from the backend's own schedule and sizing code (nothing is stored). */
export type LoanPreview = Schemas["LoanApplicationPreview"];

/** The apply flow, one URL per step, so a refresh or the back button never loses where the member was. */
export const STEPS = [
  { id: "eligibility", label: "What's unlocked" },
  { id: "amount", label: "Amount" },
  { id: "consent", label: "Your OK" },
  { id: "check", label: "Fair check" },
  { id: "review", label: "Review" },
] as const;

export type StepId = (typeof STEPS)[number]["id"];

export function isStepId(value: string | undefined): value is StepId {
  return STEPS.some((step) => step.id === value);
}

/** What the member typed so far. Plain whole dollars; the backend turns them into cents and judges them. */
export interface WizardParams {
  amount?: number;
  term?: number;
  purpose?: string;
  dueDay?: number;
  assessment?: string;
}

export function applyHref(step: StepId, params: WizardParams = {}): Route {
  const query = new URLSearchParams({ step });
  if (params.amount !== undefined) query.set("amount", String(params.amount));
  if (params.term !== undefined) query.set("term", String(params.term));
  if (params.purpose) query.set("purpose", params.purpose);
  if (params.dueDay !== undefined) query.set("dueDay", String(params.dueDay));
  if (params.assessment) query.set("assessment", params.assessment);
  return `/app/apply?${query}` as Route;
}

export const PURPOSES = [
  "An emergency expense",
  "Car or bike repair",
  "Rent or housing",
  "Medical or dental",
  "School or training",
  "Tools or equipment for work",
  "Something else",
] as const;

/** Lowest loan the backend accepts today; the field uses it as a floor and the backend still decides. */
export const MIN_LOAN_DOLLARS = 50;

export interface PreviewNotice {
  message: string;
  /** A safe alternative the backend suggests, in the units the amount step uses. */
  suggestion: { amountDollars: number; term: number } | null;
}

/**
 * Plain words for a preview the member cannot apply for as chosen, picked by the backend's `outcome`. Every figure comes
 * from the preview's own fields. The backend's English reasons are not shown: they name internal ladder steps.
 */
export function previewNotice(preview: LoanPreview): PreviewNotice | null {
  if (preview.valid) {
    return null;
  }
  const suggestedAmount = preview.suggestedAmountCents ?? null;
  const suggestedTerm = preview.suggestedTermMonths ?? null;
  const suggestion =
    suggestedAmount !== null && suggestedTerm !== null
      ? { amountDollars: Math.round(suggestedAmount / 100), term: suggestedTerm }
      : null;
  switch (preview.outcome) {
    case "ABOVE_LIMIT":
      return { message: `The most you can borrow right now is ${formatMoney(preview.limitCents)}.`, suggestion };
    case "INSTALMENT_ABOVE_CAP":
      return {
        message: `Over ${preview.termMonths} months that would be ${formatMoney(preview.monthlyInstalmentCents)} a month. We keep payments at ${formatMoney(preview.maxMonthlyInstalmentCents)} a month or less, so they stay comfortable.`,
        suggestion,
      };
    case "TERM_OUT_OF_RANGE":
      return { message: "That number of months is not available for this loan.", suggestion };
    case "AMOUNT_NOT_ALLOWED":
      return { message: `Choose a whole-dollar amount of ${formatMoney(MIN_LOAN_DOLLARS * 100)} or more.`, suggestion };
    default:
      return { message: "Borrowing is not open to you just yet. Your steady saving is what opens it.", suggestion: null };
  }
}

/** The record level in plain words. A level is published to the member already; it is never a score. */
export const RECORD_LEVEL_WORDS: Record<RecordLevel, string> = {
  NEW: "New. Your Seed record is just starting.",
  BUILDING: "Building. Your saving is starting to add up.",
  STEADY: "Steady. You have kept a steady rhythm.",
  TRUSTED: "Trusted. A long, reliable record with Seed.",
  EXEMPLARY: "Exemplary. The strongest record Seed recognises.",
};
