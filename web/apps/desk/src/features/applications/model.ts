import type { Schemas } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";

export type ApplicationStatus = Schemas["ApplicationStatus"];
export type AssessmentOutcome = Schemas["AssessmentOutcome"];
export type SlaState = Schemas["SlaState"];
export type DeskApplicationRow = Schemas["DeskApplicationRow"];
export type LoanApplication = Schemas["LoanApplication"];
export type FairCheckCard = Schemas["FairCheckCard"];

export const APPLICATION_STATUSES: Record<ApplicationStatus, { label: string; tone: StatusTone; open: boolean }> = {
  SUBMITTED: { label: "Awaiting first approval", tone: "warning", open: true },
  PENDING_SECOND_APPROVAL: { label: "Awaiting second approval", tone: "warning", open: true },
  APPROVED: { label: "Approved", tone: "info", open: false },
  DISBURSING: { label: "Paying out", tone: "info", open: false },
  DISBURSED: { label: "Paid out", tone: "success", open: false },
  REJECTED: { label: "Rejected", tone: "danger", open: false },
  CANCELLED_COOLING_OFF: { label: "Cancelled by member", tone: "neutral", open: false },
};

export const STATUS_ORDER = Object.keys(APPLICATION_STATUSES) as ApplicationStatus[];

export const OUTCOMES: Record<AssessmentOutcome, { label: string; tone: StatusTone }> = {
  RECOMMENDED: { label: "Recommended", tone: "success" },
  NOT_RECOMMENDED: { label: "Not recommended", tone: "warning" },
  APPROVED: { label: "Officer approved", tone: "success" },
  ADJUSTED: { label: "Officer adjusted", tone: "info" },
  DECLINED: { label: "Officer declined", tone: "danger" },
};

export const SLA_STATES: Record<SlaState, { label: string; tone: StatusTone } | null> = {
  OVERDUE: { label: "Breached", tone: "danger" },
  DUE_SOON: { label: "Due soon", tone: "warning" },
  ON_TRACK: null,
  NO_SLA: null,
};

/** The filters the queue offers; the route handler validates the same shape. */
export interface ApplicationFilters {
  status: ApplicationStatus[];
  slaBreached: boolean;
}

export const NO_FILTERS: ApplicationFilters = { status: [], slaBreached: false };

/** `0197f3a4-…` → `0197f3a4`: enough to tell members apart on a screen; the full id stays in the link. */
export function shortRef(id: string): string {
  return id.slice(0, 8);
}

/**
 * Why PayPal did not complete a payout, in words an officer can act on. The backend sends a PayPal reason name
 * (for example `RECEIVER_UNREGISTERED`); it is matched here and never shown.
 */
export function explainPayoutIssue(issue: string): string {
  const reason = issue.toUpperCase();
  if (reason.includes("UNREGISTERED") || reason.includes("UNCONFIRMED") || reason.includes("RECEIVER")) {
    return "PayPal could not find a PayPal account for the member's email or phone. Ask the member to check the PayPal account linked to Seed.";
  }
  if (reason.includes("FUNDS") || reason.includes("BALANCE") || reason.includes("INSUFFICIENT")) {
    return "Seed's payout balance was too low for this loan. Add funds to the payout account, then try again.";
  }
  if (reason.includes("LIMIT") || reason.includes("COMPLIANCE") || reason.includes("BLOCKED") || reason.includes("RISK")) {
    return "PayPal held this payout for its own review. Nothing was paid. You can try again, or contact PayPal support if it keeps happening.";
  }
  return "PayPal could not complete this payout. Nothing was paid and the application is still approved.";
}

export type TimelineState = "done" | "current" | "todo" | "stopped";

export interface TimelineStep {
  key: string;
  label: string;
  detail?: string;
  state: TimelineState;
}

/**
 * The application's path, read from the facts on the application (the contract has no event log for it): who
 * approved, whether a payout is running, whether the loan exists. Times are shown only where the contract has them.
 */
export function buildTimeline(application: LoanApplication, staffName: (id: string) => string): TimelineStep[] {
  const { status } = application;
  const first = application.firstApproverId ? staffName(application.firstApproverId) : undefined;
  const second = application.secondApproverId ? staffName(application.secondApproverId) : undefined;
  const steps: TimelineStep[] = [{ key: "submitted", label: "Submitted by the member", state: "done" }];

  if (status === "CANCELLED_COOLING_OFF") {
    steps.push({ key: "cancelled", label: "Cancelled by the member in the cooling-off window", state: "stopped" });
    return steps;
  }

  steps.push({
    key: "first",
    label: "First approval",
    ...(first ? { detail: `Signed by ${first}` } : {}),
    state: first ? "done" : status === "SUBMITTED" ? "current" : "stopped",
  });

  if (status === "REJECTED") {
    steps.push({
      key: "rejected",
      label: "Rejected",
      ...(application.rejectionReason ? { detail: application.rejectionReason } : {}),
      state: "stopped",
    });
    return steps;
  }

  steps.push({
    key: "second",
    label: "Second approval by a different officer",
    ...(second ? { detail: `Signed by ${second}` } : {}),
    state: second ? "done" : status === "PENDING_SECOND_APPROVAL" ? "current" : "todo",
  });

  const paid = status === "DISBURSED";
  steps.push({
    key: "payout",
    label: paid ? "Paid out through PayPal" : status === "DISBURSING" ? "Payout in progress" : "Pay out through PayPal",
    ...(application.payoutIssue && status === "APPROVED" ? { detail: "The last payout did not complete." } : {}),
    state: paid ? "done" : status === "DISBURSING" || status === "APPROVED" ? "current" : "todo",
  });
  steps.push({
    key: "loan",
    label: "Loan and repayment schedule created",
    state: paid ? "done" : "todo",
  });
  return steps;
}
