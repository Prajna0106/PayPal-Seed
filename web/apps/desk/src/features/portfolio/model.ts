import type { Schemas } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";

/** Desk wording for portfolio values. The figures come from the API; this file only names them. */

export type LoanStatus = Schemas["LoanStatus"];
export type AgingBucket = Schemas["AgingBucket"];

export const LOAN_STATUSES: Record<LoanStatus, { label: string; tone: StatusTone }> = {
  ACTIVE: { label: "Active", tone: "info" },
  REPAID: { label: "Repaid", tone: "success" },
  COVERED: { label: "Covered by reserve", tone: "neutral" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
  DISBURSEMENT_RETURNED: { label: "Payout returned", tone: "warning" },
};

/** Severity order, mildest first, the order the backend lists the buckets in. */
export const AGING_BUCKETS: Record<AgingBucket, { label: string; tone: StatusTone; fill: string }> = {
  CURRENT: { label: "Current", tone: "success", fill: "var(--color-success)" },
  DPD_1_7: { label: "1 to 7 days late", tone: "info", fill: "var(--color-chart-4)" },
  DPD_8_30: { label: "8 to 30 days late", tone: "warning", fill: "var(--color-chart-2)" },
  DPD_31_60: { label: "31 to 60 days late", tone: "warning", fill: "var(--color-warning)" },
  DPD_60_PLUS: { label: "Over 60 days late", tone: "danger", fill: "var(--color-danger)" },
};

export const BUCKET_ORDER = Object.keys(AGING_BUCKETS) as AgingBucket[];
export const STATUS_ORDER = Object.keys(LOAN_STATUSES) as LoanStatus[];

/** Grid columns the backend lets a list sort by (`listDeskPortfolioLoans`). Anything else is refused. */
export const SORT_COLUMNS = [
  "disbursedOn",
  "principalCents",
  "outstandingCents",
  "nextDueDate",
  "daysLate",
  "agingBucket",
  "status",
] as const;
export type SortColumn = (typeof SORT_COLUMNS)[number];
