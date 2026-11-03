import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { formatBusinessDate, formatDateTime, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";

/**
 * Desk wording for fraud flags (ADR 0019). Flags are advice for officers: nothing in Seed refuses, limits or prices
 * anything because of one. Every number shown comes from the API; this module only names and formats it.
 */

export type FlagStatus = "OPEN" | "REVIEWED_CLEAR" | "CONFIRMED";

export const FLAG_STATUSES: Record<FlagStatus, { label: string; tone: StatusTone }> = {
  OPEN: { label: "Open", tone: "warning" },
  REVIEWED_CLEAR: { label: "Cleared", tone: "success" },
  CONFIRMED: { label: "Confirmed", tone: "danger" },
};

export const FLAG_FILTERS = {
  open: { label: "Open", statuses: ["OPEN"] as FlagStatus[] },
  cleared: { label: "Cleared", statuses: ["REVIEWED_CLEAR"] as FlagStatus[] },
  confirmed: { label: "Confirmed", statuses: ["CONFIRMED"] as FlagStatus[] },
  all: { label: "All", statuses: [] as FlagStatus[] },
} as const;

export type FlagFilter = keyof typeof FLAG_FILTERS;

export function readFlagFilter(value: string | string[] | undefined): FlagFilter {
  const first = Array.isArray(value) ? value[0] : value;
  return first && first in FLAG_FILTERS ? (first as FlagFilter) : "open";
}

/** Plain-language rule names and what each one looks for (ADR 0019, `fraud-rules-v1`). */
export const SIGNALS: Record<string, { name: string; what: string }> = {
  GOAL_VELOCITY: { name: "Many new goals quickly", what: "Four or more new savings goals in 24 hours." },
  APPLICATION_VELOCITY: { name: "Repeated loan applications", what: "Two or more applications in 7 days." },
  SHARED_PAYER: { name: "One payer behind several members", what: "The same PayPal payer also pays for another member." },
  DEPOSIT_CHURN: { name: "Money in, then straight out", what: "Most of a withdrawal of $25 or more arrived in the 48 hours before." },
  NEW_ACCOUNT_LOAN: { name: "Loan soon after sign-up", what: "An application or payout within 72 hours of the account starting." },
  REPEATED_FAILED_PAYMENTS: { name: "Repeated failed payments", what: "Three or more failed payments in 30 days." },
  PAYOUT_RECIPIENT_CHANGE: { name: "Payout destination changed", what: "The payout recipient changed in the 72 hours before a disbursement." },
};

export const signalName = (code: string): string => SIGNALS[code]?.name ?? code;

export const FACT_KINDS: Record<string, string> = {
  ACCOUNT_CREATED: "Account created",
  GOAL_CREATED: "Savings goal created",
  DEPOSIT: "Deposit",
  WITHDRAWAL: "Withdrawal",
  APPLICATION_SUBMITTED: "Loan application submitted",
  LOAN_DISBURSED: "Loan paid out",
  PAYMENT_FAILED: "Payment failed",
  PAYER_SEEN: "Payer seen",
  PAYOUT_RECIPIENT_CHANGED: "Payout recipient changed",
};

/** `windowHours` becomes "Window hours"; keys with a unit suffix are formatted by it. */
function humanise(key: string): string {
  const base = key.replace(/(Cents|Bps|Date)$/, "").replace(/(?<=[a-z0-9])(?=[A-Z])/g, " ");
  return base.charAt(0).toUpperCase() + base.slice(1).toLowerCase();
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T/;

function formatValue(key: string, value: unknown): string {
  if (Array.isArray(value)) {
    return value.map((item) => formatValue(key, item)).join(", ");
  }
  if (typeof value === "string") {
    if (ISO_DATE.test(value)) {
      return formatBusinessDate(value);
    }
    return ISO_INSTANT.test(value) ? formatDateTime(value) : value;
  }
  if (typeof value === "boolean") {
    return value ? "Yes" : "No";
  }
  if (typeof value !== "number") {
    return "—";
  }
  if (key.endsWith("Cents")) {
    return formatMoney(value);
  }
  if (key.endsWith("Bps")) {
    return formatPercentBps(value);
  }
  return value.toLocaleString("en-US");
}

export interface Figure {
  key: string;
  label: string;
  value: string;
}

/** The numbers, windows and fact ids a rule used, named for people. Objects nested inside are shown as text. */
export function evidenceFigures(evidence: Record<string, unknown> | undefined): Figure[] {
  return Object.entries(evidence ?? {}).map(([key, value]) => ({
    key,
    label: humanise(key),
    value: typeof value === "object" && value !== null && !Array.isArray(value) ? JSON.stringify(value) : formatValue(key, value),
  }));
}

const PROBLEM_TEXT: Record<string, string> = {
  "fraud-flag-already-reviewed": "Another officer already reviewed this flag. Refresh to see the outcome; new evidence later opens a new flag.",
  "fraud-note-required": "Add a note that explains why you are confirming this flag.",
  "idempotency-key-reused": "This review was already sent with different details. Refresh the page and review again.",
};

/** Plain words for any error from the fraud endpoints. Never shows raw JSON or a stack trace. */
export function fraudErrorMessage(error: unknown): string {
  if (isProblem(error)) {
    const text = PROBLEM_TEXT[error.code];
    if (text) {
      return text;
    }
    if (error.status === 403) {
      return "Your role cannot see or review fraud flags. They are for officers, checkers and admins; only officers and admins can review.";
    }
    if (error.status === 401) {
      return "Please sign in again.";
    }
    if (error.status === 404) {
      return "That flag no longer exists.";
    }
    return problemMessage(error);
  }
  return "We could not reach the Seed backend. Try again in a moment.";
}
