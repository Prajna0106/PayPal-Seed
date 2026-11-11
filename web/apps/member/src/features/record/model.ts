import type { Schemas } from "@paypal-seed/api-client";

export type RecordView = Schemas["RecordView"];
export type RecordLevel = Schemas["RecordLevel"];
export type HistoryItem = Schemas["HistoryItem"];
export type ShareView = Schemas["ShareView"];
export type ShareDetail = Schemas["ShareDetail"];
export type VerifiedRecord = Schemas["VerifiedRecord"];
export type TrustRecord = Schemas["TrustRecord"];

export const LEVEL_NAME: Record<RecordLevel, string> = {
  NEW: "New",
  BUILDING: "Building",
  STEADY: "Steady",
  TRUSTED: "Trusted",
  EXEMPLARY: "Exemplary",
};

export const LEVEL_BLURB: Record<RecordLevel, string> = {
  NEW: "Your record starts here. Saving on time and repaying on plan builds it.",
  BUILDING: "You have started a steady habit. Keep going.",
  STEADY: "A solid history of saving and repaying.",
  TRUSTED: "A strong, reliable history.",
  EXEMPLARY: "The highest level. Thank you for being so reliable.",
};

export const DETAIL_CHOICES: ReadonlyArray<{ value: ShareDetail; title: string; description: string }> = [
  { value: "LEVEL", title: "Level only", description: "Just your level." },
  { value: "SUMMARY", title: "Summary", description: "Level, when your record began and loans repaid on plan." },
  { value: "FULL", title: "Full", description: "Everything in the summary, plus how often you pay on time." },
];

export const EXPIRY_CHOICES: ReadonlyArray<{ days: number; label: string }> = [
  { days: 1, label: "1 day" },
  { days: 7, label: "7 days" },
  { days: 14, label: "14 days" },
  { days: 30, label: "30 days" },
];

export function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/** What one fact in the history means, in plain words. Never blames the member. */
export function describeFact(item: HistoryItem): string {
  switch (item.kind) {
    case "SAVINGS_DEPOSIT":
      return "You added to your savings";
    case "SAVING_MONTHS_CLOSED":
      return "A saving month was counted";
    case "LOAN_DISBURSED":
      return "You started a loan";
    case "INSTALMENT_PAID":
      return item.daysLate && item.daysLate > 0
        ? `A loan payment was made ${plural(item.daysLate, "day", "days")} after its due date`
        : "A loan payment was made on time";
    case "INSTALMENT_MISSED":
      return "A loan payment was not made by its due date";
    case "LOAN_REPAID":
      return "You finished repaying a loan";
    case "LOAN_DEFAULTED":
      return "A loan was closed unpaid";
    case "INSTALMENT_FUND_COVERED":
      return "The community fund helped with a loan payment";
    case "HARDSHIP_PLAN_APPLIED":
      return "A hardship plan was applied. This never lowers your record";
  }
}
