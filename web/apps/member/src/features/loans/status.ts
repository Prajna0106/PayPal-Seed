import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import type { InstalmentStatus } from "./model";

/** Plain words and a quiet tone for each instalment status. Shared by the table and the timeline. */
export const INSTALMENT_STATUS: Record<InstalmentStatus, { label: string; tone: StatusTone }> = {
  SCHEDULED: { label: "Upcoming", tone: "neutral" },
  DUE: { label: "Due", tone: "info" },
  PARTIALLY_PAID: { label: "Partly paid", tone: "info" },
  IN_GRACE: { label: "In grace period", tone: "warning" },
  LATE: { label: "Late", tone: "danger" },
  PAID: { label: "Paid", tone: "success" },
  PAID_LATE: { label: "Paid", tone: "success" },
  PREPAID: { label: "Paid early", tone: "success" },
};

/** Labels the timeline uses, keyed by the backend's status codes (plus the ghost marker). */
export const TIMELINE_STATUS_LABELS: Record<string, string> = {
  ...Object.fromEntries(Object.entries(INSTALMENT_STATUS).map(([code, { label }]) => [code, label])),
  PREVIOUS_PLAN: "Before the change",
};

/** Plain words for a loan that is no longer active. A returned payout is never the member's doing: nothing is owed. */
export const LOAN_ENDED: Record<string, { label: string; tone: StatusTone; title: string }> = {
  REPAID: { label: "Paid off", tone: "success", title: "This loan is paid off" },
  COVERED: { label: "Completed with fund help", tone: "success", title: "This loan was completed with help from the community fund" },
  CANCELLED: { label: "Returned", tone: "neutral", title: "This loan was returned" },
  DISBURSEMENT_RETURNED: { label: "Money not received", tone: "neutral", title: "The money did not reach your PayPal account" },
};

export function loanEnded(status: string): { label: string; tone: StatusTone; title: string } {
  return LOAN_ENDED[status] ?? { label: "Closed", tone: "neutral", title: "This loan is closed" };
}
