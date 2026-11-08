import "server-only";

import { ApiProblem, isProblem, problemMessage } from "@paypal-seed/api-client";

/**
 * Plain words for what can go wrong around savings. Some savings routes answer with a bare `{ "error": ... }` body
 * instead of a problem document, so those show up here as a status only (`http-409`) and are explained by status.
 */
const BY_CODE: Record<string, string> = {
  "payout-recipient-missing":
    "We do not know where to send your money yet. Please sign in with PayPal again, then try once more.",
  "amount-not-whole-dollars": "PayPal saves whole dollars each month. Please choose an amount without cents.",
  "idempotency-key-reused": "This was already sent with different details. Please reload the page and try again.",
  "topup-not-allowed": "This goal cannot take a top-up right now.",
  "dependency-unavailable": "PayPal is not answering right now. Nothing was changed, so you can try again in a moment.",
  "legacy-id-not-allowed": "We could not start this goal. Please try again.",
  "paypal-rejected": "PayPal did not accept this request, so nothing was started or taken. Please try again in a little while.",
  "subscription-unavailable": "This goal saves from Venmo with top-ups, so it has no monthly plan to change.",
};

export type SavingsAction = "create" | "pause" | "resume" | "withdraw" | "topup" | "amount";

const CONFLICT: Record<SavingsAction, string> = {
  create: "You already have three goals open. Finish or close one before starting another.",
  pause: "This goal cannot be paused right now.",
  resume: "This goal cannot be resumed right now.",
  withdraw: "This goal cannot pay out right now. It may still be waiting for you at PayPal.",
  topup: "This goal cannot take a top-up right now.",
  amount: "The amount cannot be changed right now. If the goal is waiting for you at PayPal, finish that first.",
};

export function savingsMessage(error: unknown, action: SavingsAction): string {
  if (isProblem(error)) {
    const known = BY_CODE[error.code];
    if (known) {
      return known;
    }
    if (error.status === 409) {
      return CONFLICT[action];
    }
    if (error.status === 503) {
      return BY_CODE["dependency-unavailable"] ?? "Please try again in a moment.";
    }
    if (error.status === 400 || error.status === 422) {
      return error.code.startsWith("http-") ? "Please check the details and try again." : problemMessage(error);
    }
  }
  return "Something went wrong on our side. Please try again.";
}

export function isMissingRecipient(error: unknown): boolean {
  return error instanceof ApiProblem && error.code === "payout-recipient-missing";
}
