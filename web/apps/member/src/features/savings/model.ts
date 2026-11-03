/**
 * View models for savings. Every figure is copied from the backend; the only conversions are dollars to cents (the
 * savings routes speak decimal dollars) and a display ratio for a progress bar.
 */
import type { Schemas } from "@paypal-seed/api-client";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";

export type GoalStatus = Schemas["GoalStatus"];
export type Wallet = Schemas["Wallet"];
export type WithdrawalStatus = Schemas["Withdrawal"]["status"];

export interface Goal {
  id: string;
  name: string;
  status: GoalStatus;
  wallet: Wallet;
  targetCents: number;
  monthlyCents: number;
  /** Money in the goal that is free to use; a withdrawal on its way is in `reservedCents`. */
  savedCents: number;
  reservedCents: number;
  targetDate: string | null;
  approvalUrl: string | null;
  approvalExpiresAt: string | null;
  createdAt: string;
}

export interface Withdrawal {
  id: string;
  status: WithdrawalStatus;
  amountCents: number;
  wallet: Wallet;
  createdAt: string;
}

/** The contract uses decimal dollars for savings; convert once here and keep cents everywhere else. */
export function dollarsToCents(dollars: number): number {
  return Math.round(dollars * 100);
}

/** Dollars typed by a person (`25`, `25.5`) as cents, or `null` when it is not an amount. */
export function parseDollars(value: string): number | null {
  const text = value.trim();
  return /^\d{1,7}(\.\d{1,2})?$/.test(text) ? Math.round(Number(text) * 100) : null;
}

/** Cents as the plain dollars a person types back into a field (`2500` becomes `25`, `2550` becomes `25.50`). */
export function centsToField(cents: number): string {
  return cents % 100 === 0 ? String(cents / 100) : (cents / 100).toFixed(2);
}

export function toGoal(goal: Schemas["SavingsGoal"]): Goal {
  return {
    id: goal.id,
    name: goal.name,
    status: goal.status,
    wallet: goal.wallet,
    targetCents: dollarsToCents(goal.targetAmount),
    monthlyCents: dollarsToCents(goal.monthlyAmount),
    savedCents: dollarsToCents(goal.currentBalance),
    reservedCents: dollarsToCents(goal.reservedAmount),
    targetDate: goal.targetDate ?? null,
    approvalUrl: goal.approvalUrl ?? null,
    approvalExpiresAt: goal.approvalExpiresAt ?? null,
    createdAt: goal.createdAt,
  };
}

/** One payment PayPal confirmed and Seed credited to a goal. `amountCents` is the backend's own whole-cent figure. */
export interface Deposit {
  source: Schemas["GoalDeposit"]["source"];
  amountCents: number;
  creditedAt: string;
}

export function toDeposit(deposit: Schemas["GoalDeposit"]): Deposit {
  return { source: deposit.source, amountCents: deposit.amountCents, creditedAt: deposit.creditedAt };
}

export const DEPOSIT_SOURCE: Record<Deposit["source"], string> = {
  SUBSCRIPTION: "Monthly deposit",
  TOPUP: "Extra deposit",
};

export function toWithdrawal(withdrawal: Schemas["Withdrawal"]): Withdrawal {
  return {
    id: withdrawal.id,
    status: withdrawal.status,
    amountCents: dollarsToCents(withdrawal.amount),
    wallet: withdrawal.wallet,
    createdAt: withdrawal.createdAt,
  };
}

export const GOAL_STATUS: Record<GoalStatus, { label: string; tone: StatusTone; hint: string }> = {
  PENDING_APPROVAL: { label: "Waiting for you at PayPal", tone: "warning", hint: "Nothing is saved until you agree at PayPal." },
  ACTIVE: { label: "Saving", tone: "success", hint: "Your monthly amount is on its way each month." },
  PAUSED: { label: "Paused", tone: "neutral", hint: "No money is taken while you are paused." },
  COMPLETED: { label: "Goal reached", tone: "success", hint: "You reached your target. Well done." },
  CANCELLED: { label: "Closed", tone: "neutral", hint: "This goal was closed. Your money stays yours." },
  EXPIRED: { label: "Not set up", tone: "neutral", hint: "We did not hear back from PayPal in time, so nothing was set up." },
};

export const WITHDRAWAL_STATUS: Record<WithdrawalStatus, { label: string; tone: StatusTone; hint: string }> = {
  REQUESTED: { label: "On its way", tone: "info", hint: "PayPal is sending it. This can take a little while." },
  SUCCEEDED: { label: "Sent", tone: "success", hint: "It reached your PayPal account." },
  FAILED: { label: "Did not go through", tone: "warning", hint: "The money is back in your goal." },
  RETURNED: { label: "Came back", tone: "warning", hint: "PayPal returned it, so the money is back in your goal." },
};

export const WALLET_LABEL: Record<Wallet, string> = { PAYPAL: "PayPal", VENMO: "Venmo" };

/** Goals that take part in saving: money can still arrive, or has arrived before. */
export function isOpen(status: GoalStatus): boolean {
  return status === "ACTIVE" || status === "PAUSED" || status === "PENDING_APPROVAL";
}

/** Goals that can take a one-off top-up (the backend refuses pending, closed and expired goals). */
export function canTopUp(status: GoalStatus): boolean {
  return status === "ACTIVE" || status === "PAUSED" || status === "COMPLETED";
}

/** Goals that hold money which has been approved, so it can be taken out. */
export function canWithdraw(goal: Goal): boolean {
  return goal.status !== "PENDING_APPROVAL" && goal.status !== "EXPIRED" && goal.savedCents > 0;
}

/** 0 to 100 for a progress bar. Display only. */
export function progressPercent(goal: Goal): number {
  return goal.targetCents > 0 ? Math.min(100, Math.max(0, (goal.savedCents / goal.targetCents) * 100)) : 0;
}

/**
 * Where PayPal asks the member to agree. Only a PayPal address is followed, so a wrong value in the data can never
 * send a member somewhere else.
 */
export function safeApprovalUrl(url: string | null): string | null {
  if (!url) {
    return null;
  }
  try {
    const parsed = new URL(url);
    const paypalHost = parsed.hostname === "paypal.com" || parsed.hostname.endsWith(".paypal.com");
    return parsed.protocol === "https:" && paypalHost ? parsed.toString() : null;
  } catch {
    return null;
  }
}

/** `YYYY-MM-DD` of an instant in the program's time zone (the same zone `formatDateTime` uses). */
export function dateInProgramZone(instant: string): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Los_Angeles", year: "numeric", month: "2-digit", day: "2-digit" }).format(
    new Date(instant),
  );
}
