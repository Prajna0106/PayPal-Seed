/** Member-facing words for backend codes. Plain, short, and never a risk level or a score. */
import type { ActivityKind } from "./model";

export const SECTION_LABEL = {
  profile: "your profile",
  savings: "your savings",
  lending: "your loan and borrowing",
  underwriting: "your fair loan check",
  creditRecord: "your Seed record",
  hardship: "your payment help",
  recentActivity: "your recent activity",
} as const;

/** The mandate state payments reports, as a short label. */
export function autopayLabel(status: string | undefined): string {
  switch (status) {
    case "ACTIVE":
      return "Autopay on";
    case "APPROVAL_PENDING":
      return "Autopay waiting for you";
    case "SUSPENDED":
      return "Autopay paused";
    case "CANCELLED":
    case "EXPIRED":
      return "Autopay off";
    default:
      return "Pay as you go";
  }
}

export function autopayTone(status: string | undefined): "success" | "warning" | "neutral" {
  if (status === "ACTIVE") {
    return "success";
  }
  return status === "SUSPENDED" || status === "APPROVAL_PENDING" ? "warning" : "neutral";
}

const APPLICATION_STATUS: Record<string, { label: string; detail: string; tone: "info" | "success" | "neutral" }> = {
  SUBMITTED: { label: "With our team", detail: "A person is looking at your application.", tone: "info" },
  PENDING_SECOND_APPROVAL: { label: "Second look", detail: "A second person is checking it, as we always do.", tone: "info" },
  APPROVED: { label: "Approved", detail: "Your loan is approved and being set up.", tone: "success" },
  DISBURSING: { label: "Sending your money", detail: "Your money is on its way to PayPal.", tone: "success" },
};

export function applicationStatus(status: string): { label: string; detail: string; tone: "info" | "success" | "neutral" } {
  return APPLICATION_STATUS[status] ?? { label: "In progress", detail: "We are working on your application.", tone: "neutral" };
}

export const PAYOUT_RETURNED =
  "The money did not reach your PayPal account, you owe nothing, we will try again once your account is ready.";

export const PAYOUT_ISSUE = "Your payout did not reach your PayPal account yet. We will try again, and you can check your account details.";

export const LEVELS = ["NEW", "BUILDING", "STEADY", "TRUSTED", "EXEMPLARY"] as const;

const LEVEL_LABEL: Record<string, string> = {
  NEW: "New",
  BUILDING: "Building",
  STEADY: "Steady",
  TRUSTED: "Trusted",
  EXEMPLARY: "Exemplary",
};

export function levelLabel(level: string): string {
  return LEVEL_LABEL[level] ?? level.toLowerCase();
}

/** Case states where the member has already chosen and a person is reviewing (nothing for them to do). */
export const HELP_REVIEWING: ReadonlySet<string> = new Set(["OPTION_CHOSEN", "PENDING_APPROVAL", "APPROVED"]);

export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/**
 * Plain words for each kind of thing that happened. The community fund line never says why it helped: the backend sends
 * no hardship detail and the member needs none.
 */
export const ACTIVITY_WORDS: Record<ActivityKind, { title: string; detail: string | null }> = {
  DEPOSIT: { title: "You saved", detail: null },
  WITHDRAWAL: { title: "You took money out", detail: null },
  WITHDRAWAL_RETURNED: { title: "A withdrawal came back", detail: "The money is in your goal again." },
  LOAN_DISBURSED: { title: "Your loan was sent", detail: null },
  LOAN_PAYMENT: { title: "You made a loan payment", detail: null },
  LOAN_PAYMENT_REVERSED: { title: "A loan payment was taken back", detail: "PayPal reversed it, so it counts as not paid yet." },
  COVER_APPLIED: { title: "The community fund helped with a payment", detail: null },
  LOAN_VOIDED: { title: "Your loan payout came back", detail: "You owe nothing for it." },
};
