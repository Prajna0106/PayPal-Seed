import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import type { Schemas } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { formatDateTime } from "@paypal-seed/ui/lib/format";

/**
 * Desk wording for the reserve (ADR 0019). Every figure on screen comes from the API; this module only names values.
 */

export type CoverStatus = "PENDING_APPROVAL" | "APPROVED" | "REJECTED" | "FAILED";

export const COVER_STATUSES: Record<CoverStatus, { label: string; tone: StatusTone }> = {
  PENDING_APPROVAL: { label: "Requested", tone: "warning" },
  APPROVED: { label: "Approved", tone: "success" },
  REJECTED: { label: "Rejected", tone: "danger" },
  FAILED: { label: "Failed", tone: "danger" },
};

export const COVER_STATUS_ORDER = Object.keys(COVER_STATUSES) as CoverStatus[];

/** Filters in the URL (`?status=requested`) map to the statuses the backend understands. */
export const COVER_FILTERS = {
  all: { label: "All", statuses: [] as CoverStatus[] },
  requested: { label: "Requested", statuses: ["PENDING_APPROVAL"] as CoverStatus[] },
  approved: { label: "Approved", statuses: ["APPROVED"] as CoverStatus[] },
  rejected: { label: "Rejected", statuses: ["REJECTED", "FAILED"] as CoverStatus[] },
} as const;

export type CoverFilter = keyof typeof COVER_FILTERS;

export function readCoverFilter(value: string | string[] | undefined): CoverFilter {
  const first = Array.isArray(value) ? value[0] : value;
  return first && first in COVER_FILTERS ? (first as CoverFilter) : "all";
}

/**
 * Default limits from ADR 0019 (`seed.reserve.cover.*`). They are configuration on the server, so they are only
 * shown as guidance here and never enforced in the browser: the server refuses what is over the limit.
 */
export const DEFAULT_CAPS = { perRequestCents: 50_000, perMemberYearCents: 100_000 } as const;

/** The decision window the backend applies to a cover request (`seed.reserve.cover.decision-sla`, ISO 8601). */
export const DECISION_WINDOW = "48 hours";

/** One line of "who has signed": already named and formatted, so it can cross to a client component as plain text. */
export interface SignatureLine {
  role: "MAKER" | "CHECKER";
  roleLabel: string;
  /** The officer's name from the staff directory, or a short id when the directory could not be read. */
  who: string;
  /** Null while the signature is still missing. */
  at: string | null;
}

export interface CoverProgress {
  signatures: SignatureLine[];
  /** What has to happen next, in words. Empty once the request is closed. */
  next: string;
  /** The deadline, formatted; null when the backend could not read the approval request. */
  dueLabel: string | null;
  /** Open past its deadline. Decided by the server from the backend's `slaDueAt`; the browser never compares times. */
  breached: boolean;
}

const ROLE_LABELS = { MAKER: "Maker", CHECKER: "Checker" } as const;

/**
 * Maker and checker for one cover request, from the `approvals` and `slaDueAt` the backend sends. `asOf` is the server's
 * clock at the time of the read, so a breach is decided once, in one place.
 */
export function coverProgress(cover: Schemas["CoverRequest"], staffName: (staffId: string) => string, asOf: number): CoverProgress {
  const open = cover.status === "PENDING_APPROVAL";
  const approvals = cover.approvals ?? [];
  const signatures: SignatureLine[] = (["MAKER", "CHECKER"] as const).flatMap((role): SignatureLine[] => {
    const signed = approvals.find((approval) => approval.role === role);
    if (signed) {
      return [{ role, roleLabel: ROLE_LABELS[role], who: staffName(signed.staffId), at: formatDateTime(signed.decidedAt) }];
    }
    // A closed request will never get the missing signature, so it is not listed as waiting.
    return open ? [{ role, roleLabel: ROLE_LABELS[role], who: "Waiting", at: null }] : [];
  });
  const next = !open
    ? ""
    : approvals.length === 0
      ? "Needs a maker (officer) first."
      : "Needs a checker, a different officer from the maker.";
  const due = cover.slaDueAt ? Date.parse(cover.slaDueAt) : null;
  return {
    signatures,
    next,
    dueLabel: cover.slaDueAt ? formatDateTime(cover.slaDueAt) : null,
    breached: open && due !== null && asOf > due,
  };
}

/** Why a bridge is needed. The backend stores the code; the label is for people. */
export const RAISE_REASONS = [
  { code: "INCOME_DROP_VERIFIED", label: "Income drop verified" },
  { code: "TEMPORARY_HARDSHIP", label: "Temporary hardship" },
  { code: "DATE_CHANGE_NOT_ENOUGH", label: "Moving the date is not enough" },
  { code: "OTHER", label: "Other (explain in the note)" },
] as const;

export const DECISION_REASONS = [
  { code: "INCOME_DROP_VERIFIED", label: "Income drop verified" },
  { code: "TEMPORARY_HARDSHIP", label: "Temporary hardship" },
  { code: "DATE_CHANGE_NOT_ENOUGH", label: "Moving the date is not enough" },
  { code: "INSUFFICIENT_EVIDENCE", label: "Insufficient evidence" },
  { code: "POLICY_LIMIT", label: "Outside policy limits" },
  { code: "OTHER", label: "Other (explain in the note)" },
] as const;

export const REASON_LABELS: Record<string, string> = Object.fromEntries(
  [...RAISE_REASONS, ...DECISION_REASONS].map((r) => [r.code, r.label]),
);

/** Plain, specific messages for what the reserve endpoints refuse (contract, ADR 0019). */
const PROBLEM_TEXT: Record<string, string> = {
  "sod-violation":
    "You already signed this request. A different officer with the checker role has to make the second decision.",
  "reserve-insufficient-funds":
    "The fund does not hold enough to cover this amount right now. Ask for a smaller amount, or wait for more gifts.",
  "cover-amount-exceeds-request-cap":
    "This is above the limit for a single cover request. Ask for a smaller amount.",
  "cover-member-cap-exceeded":
    "This member has reached the yearly cover limit, counting approved and pending requests.",
  "cover-loan-not-active":
    "This loan is no longer active (repaid, closed or not open yet), so it cannot be covered.",
  "cover-exceeds-outstanding": "The amount is more than the loan still owes. Lower it to the outstanding balance or less.",
  "cover-loan-not-found": "No loan with that reference belongs to this member. Check both references.",
  "cover-request-open": "This loan already has an open cover request. Decide that one first.",
  "cover-not-awaiting-decision": "This request is not waiting for a decision any more. Refresh to see where it stands.",
  "approval-request-closed": "The approval for this request is already closed. Refresh to see the outcome.",
  "cover-request-not-found": "That cover request no longer exists.",
};

/** Plain words for any error thrown while calling the backend. Never shows raw JSON or a stack trace. */
export function reserveErrorMessage(error: unknown): string {
  if (isProblem(error)) {
    const text = PROBLEM_TEXT[error.code];
    if (text) {
      return text;
    }
    if (error.status === 403) {
      return "Your role cannot do this. Raising a request needs an officer; the second decision needs a checker.";
    }
    if (error.status === 401) {
      return "Please sign in again.";
    }
    return problemMessage(error);
  }
  return "We could not reach the Seed backend. Try again in a moment.";
}
