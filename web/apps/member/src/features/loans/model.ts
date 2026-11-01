/**
 * View models for the member's plan. Every number is copied from the backend; the only derived values are counts
 * and "which instalment comes next", which are about display, not money.
 */

export type InstalmentStatus =
  | "SCHEDULED"
  | "DUE"
  | "PARTIALLY_PAID"
  | "IN_GRACE"
  | "LATE"
  | "PAID"
  | "PAID_LATE"
  | "PREPAID";

export interface Instalment {
  seq: number;
  dueDate: string;
  amountCents: number;
  paidCents: number;
  remainingCents: number;
  status: InstalmentStatus;
  paidOn: string | null;
}

export interface Plan {
  loanId: string;
  version: number;
  reason: string;
  businessDate: string;
  principalCents: number;
  outstandingCents: number;
  overpaidCents: number;
  loanStatus: string;
  autopay: boolean;
  instalments: Instalment[];
  /** The first instalment with money still owed, or `null` when the loan is paid off. */
  next: Instalment | null;
  paidCount: number;
}

export interface Transaction {
  id: string;
  type: "RECEIPT" | "REVERSAL" | "OVERPAYMENT_REFUND";
  source: string;
  amountCents: number;
  effectiveDate: string;
}

const SETTLED: ReadonlySet<InstalmentStatus> = new Set(["PAID", "PAID_LATE", "PREPAID"]);

export function isSettled(status: InstalmentStatus): boolean {
  return SETTLED.has(status);
}

/** Statuses that mean "this one is due now", so a payment counts as catching up rather than paying early. */
const DUE_NOW: ReadonlySet<InstalmentStatus> = new Set(["DUE", "IN_GRACE", "LATE", "PARTIALLY_PAID"]);

export function isDueNow(status: InstalmentStatus): boolean {
  return DUE_NOW.has(status);
}
