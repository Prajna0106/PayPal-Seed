import type { Schemas } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";

export type AssessmentStatus = Schemas["AssessmentStatus"];
export type DeskAssessmentRow = Schemas["DeskAssessmentRow"];
export type FairCheckCard = Schemas["FairCheckCard"];

export const ASSESSMENT_STATUSES: Record<AssessmentStatus, { label: string; tone: StatusTone }> = {
  PENDING: { label: "Awaiting decision", tone: "warning" },
  APPROVED: { label: "Approved", tone: "success" },
  ADJUSTED: { label: "Adjusted", tone: "info" },
  DECLINED: { label: "Declined", tone: "danger" },
};

export const ASSESSMENT_STATUS_ORDER = Object.keys(ASSESSMENT_STATUSES) as AssessmentStatus[];

/** The queue's filters, as the client sends them and the route handler accepts them. */
export interface FairCheckFilters {
  status: AssessmentStatus[];
  disputed: boolean;
}

export const NO_FAIR_CHECK_FILTERS: FairCheckFilters = { status: [], disputed: false };

/** Longest term the contract allows; the shortest an officer may pick is the check's own suggested term. */
export const MAX_TERM_MONTHS = 18;

/** `2026-10` → `2026-10-01`, so the plain-date formatter can name the month without a local-time parse. */
export function monthStart(yearMonth: string): string {
  return `${yearMonth}-01`;
}
