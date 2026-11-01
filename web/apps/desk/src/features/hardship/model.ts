import type { HardshipCaseStatus } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { formatBusinessDate, formatMoney, formatPercentBps } from "@paypal-seed/ui/lib/format";

/**
 * Desk wording for hardship values. Every number shown comes from the API; this module only names and formats
 * them, so the queue, the case page and the overview speak the same language.
 */

export type RiskLevel = "NONE" | "WATCH" | "LIKELY_MISS" | "IMMINENT";
export type CaseOrigin = "PROACTIVE_SCAN" | "MEMBER_HELP" | "STAFF";

interface Label {
  label: string;
  tone: StatusTone;
}

export const CASE_STATUSES: Record<HardshipCaseStatus, Label & { open: boolean }> = {
  DETECTED: { label: "Detected", tone: "neutral", open: true },
  OUTREACH_SENT: { label: "Offer sent", tone: "info", open: true },
  OFFER_VIEWED: { label: "Offer viewed", tone: "info", open: true },
  OPTION_CHOSEN: { label: "Option chosen", tone: "info", open: true },
  PENDING_APPROVAL: { label: "Needs approval", tone: "warning", open: true },
  APPROVED: { label: "Approved", tone: "success", open: true },
  APPROVED_EXECUTION_FAILED: { label: "Apply failed", tone: "danger", open: true },
  APPLIED: { label: "Applied", tone: "success", open: false },
  DECLINED_BY_MEMBER: { label: "Declined", tone: "neutral", open: false },
  REJECTED_BY_OFFICER: { label: "Rejected", tone: "danger", open: false },
  EXPIRED: { label: "Expired", tone: "neutral", open: false },
  CANCELLED_PAID: { label: "Paid", tone: "success", open: false },
  SUPERSEDED: { label: "Superseded", tone: "neutral", open: false },
};

export const STATUS_ORDER = Object.keys(CASE_STATUSES) as HardshipCaseStatus[];

export const RISK_LEVELS: Record<RiskLevel, Label> = {
  NONE: { label: "None", tone: "neutral" },
  WATCH: { label: "Watch", tone: "info" },
  LIKELY_MISS: { label: "Likely to miss", tone: "warning" },
  IMMINENT: { label: "Imminent", tone: "danger" },
};

export const ORIGINS: Record<CaseOrigin, string> = {
  PROACTIVE_SCAN: "Daily scan",
  MEMBER_HELP: "Member asked",
  STAFF: "Staff",
};

export const OPTION_NAMES: Record<string, string> = {
  A: "Move the date",
  B: "Pause one month",
  C: "Smaller, longer plan",
  D: "Pay what you can",
};

export const TIER_NAMES: Record<string, string> = {
  AUTO_POLICY: "Approved by policy",
  MAKER_CHECKER: "Two officers",
  NONE: "No approval",
};

export const SIGNAL_NAMES: Record<string, string> = {
  INCOME_DROP_MODERATE: "Income dropped",
  INCOME_DROP_SEVERE: "Income dropped sharply",
  LOW_COVERAGE: "Income barely covers the payment",
  CRITICAL_COVERAGE: "Income does not cover the payment",
  AUTOPAY_FAILED: "Autopay failed",
  AUTOPAY_FAILED_TWICE: "Autopay failed twice",
  PAYDAY_SLIP: "Usual payday passed without income",
  IN_GRACE: "Payment is past due",
  RECENT_LATE: "Paid late recently",
  SAVINGS_STRESS: "Savings under strain",
  MEMBER_REQUEST: "Member asked for help",
  AI_ESCALATION: "Raised by the Guardian's second opinion",
};

export const WARNING_NAMES: Record<string, string> = {
  NOT_SUSTAINABLE_AT_CURRENT_INCOME: "Not sustainable at current income",
  LEAVES_SHORTFALL: "Leaves part of the payment due",
};

/** Reasons an officer can give. The backend stores the code; the label is for people. */
export const REASON_CODES = [
  { code: "INCOME_DROP_VERIFIED", label: "Income drop verified" },
  { code: "TEMPORARY_HARDSHIP", label: "Temporary hardship" },
  { code: "MEMBER_REQUEST_REASONABLE", label: "Member's request is reasonable" },
  { code: "INSUFFICIENT_EVIDENCE", label: "Insufficient evidence" },
  { code: "POLICY_LIMIT", label: "Outside policy limits" },
  { code: "OTHER", label: "Other (explain in the note)" },
] as const;

export const REASON_LABELS: Record<string, string> = Object.fromEntries(REASON_CODES.map((r) => [r.code, r.label]));

const EVIDENCE_NAMES: Record<string, string> = {
  dropBps: "Drop",
  last4Cents: "Last 4 weeks",
  baseline4Cents: "Usual 4 weeks",
  weeksCompared: "Weeks compared",
  coverageBps: "Covers",
  usableShareBps: "Usable share of income",
  instalmentCents: "Payment",
  recentMonthlyCents: "Recent monthly income",
};

const SUMMARY_NAMES: Record<string, string> = {
  days: "Days moved",
  months: "Months paused",
  extraMonths: "Extra months",
  instalments: "Payments",
  amountCents: "Amount",
  payNowCents: "Pay now",
  stillDueCents: "Still due",
  maxInstalmentCents: "Largest payment",
  minInstalmentCents: "Smallest payment",
  owedBeforeCount: "Payments owed before",
  movedFromDate: "Moved from",
  movedToDate: "Moved to",
  removedFromDate: "Removed from",
  addedOnDate: "Added on",
  firstDueDate: "First due",
  lastDueDate: "Last due",
  stillDueOnDate: "Still due on",
};

/** `recentMonthlyCents` → "Recent monthly"; known keys have hand-written labels. */
function humanise(key: string, known: Record<string, string>): string {
  const label = known[key];
  if (label) {
    return label;
  }
  const base = key.replace(/(Cents|Bps|Date)$/, "").replace(/(?<=[a-z0-9])(?=[A-Z])|(?<=[a-z])(?=[0-9])/g, " ");
  return base.charAt(0).toUpperCase() + base.slice(1).toLowerCase();
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Formats one API number or date by its key suffix: cents, basis points, dates, counts. */
export function formatFigure(key: string, value: unknown): string {
  if (typeof value === "string") {
    return ISO_DATE.test(value) ? formatBusinessDate(value) : value;
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

export function evidenceFigures(evidence: Record<string, unknown> | undefined): Figure[] {
  return Object.entries(evidence ?? {}).map(([key, value]) => ({
    key,
    label: humanise(key, EVIDENCE_NAMES),
    value: formatFigure(key, value),
  }));
}

export function summaryFigures(summary: Record<string, unknown> | undefined): Figure[] {
  return Object.entries(summary ?? {}).map(([key, value]) => ({
    key,
    label: humanise(key, SUMMARY_NAMES),
    value: formatFigure(key, value),
  }));
}

/** One sentence per option, built only from the option's own summary numbers. */
export function optionHeadline(primitive: string, summary: Record<string, unknown> | undefined): string {
  const s = summary ?? {};
  const f = (key: string) => formatFigure(key, s[key]);
  switch (primitive) {
    case "MOVE_DATE":
      return `Move ${f("amountCents")} from ${f("movedFromDate")} to ${f("movedToDate")}`;
    case "DEFER_TO_END":
      return `Pause ${f("amountCents")}; it moves to ${f("addedOnDate")}`;
    case "REAMORTIZE":
      return s.minInstalmentCents === s.maxInstalmentCents
        ? `${f("instalments")} payments of ${f("maxInstalmentCents")}, last on ${f("lastDueDate")}`
        : `${f("instalments")} payments of ${f("minInstalmentCents")}–${f("maxInstalmentCents")}, last on ${f("lastDueDate")}`;
    case "PAYMENT":
      return `Pay ${f("payNowCents")} now; ${f("stillDueCents")} still due ${f("stillDueOnDate")}`;
    default:
      return primitive;
  }
}

export function isOpenStatus(status: string | undefined): boolean {
  return status ? (CASE_STATUSES[status as HardshipCaseStatus]?.open ?? false) : false;
}
