import "server-only";

import { ApiProblem } from "@paypal-seed/api-client";

export interface FriendlyProblem {
  message: string;
  /** The member should sign in with PayPal again (their account is not linked). */
  relink?: boolean;
}

/** Kind, plain sentences for the problem codes a member can meet while borrowing. Never raw codes or JSON. */
const MESSAGES: Record<string, string> = {
  "income-source-unavailable":
    "We could not reach PayPal to read your income just now. Nothing was saved and nothing was decided. Please try again in a moment.",
  "paypal-account-not-linked":
    "We need your PayPal account linked before we can read your income. Signing in with PayPal again does that.",
  "member-not-found":
    "We need your PayPal account linked before we can do this. Signing in with PayPal again does that.",
  "consent-required": "To read your income we need your OK first. You can give it on the previous step.",
  "consent-version-stale": "The wording changed while you were reading. Please read it once more and confirm.",
  "ineligible-for-loan": "A loan is not available right now. Your saving streak decides this, and it keeps growing.",
  "sizing-rule-violation": "That amount is a little more than is comfortable for you right now. Please choose a smaller amount.",
  "application-not-found": "We could not find that application.",
  "application-invalid-state":
    "This cannot be cancelled right now. If the money is on its way, you can return the loan within 48 hours of receiving it.",
  "cooling-off-expired": "The 48-hour window to return this loan has ended. You can still pay it off early, at no cost.",
  "assessment-not-found": "We could not find that fair check. Please start it again.",
  "dependency-unavailable": "One of our partners did not answer in time. Nothing was saved. Please try again in a moment.",
  "idempotency-key-reused": "That was already sent with different details. Please refresh the page and try again.",
  "unknown-wallet-preference": "Please choose PayPal or Venmo.",
  "unsupported-language": "Please choose English or Spanish.",
  "invalid-name": "Please enter the name you would like us to use.",
};

/** Codes where the backend's own sentence is written for people and is worth showing as it is. */
const BACKEND_SENTENCE = new Set(["sizing-rule-violation", "ineligible-for-loan"]);

export function friendlyProblem(error: unknown, fallback: string): FriendlyProblem {
  if (!(error instanceof ApiProblem)) {
    return { message: fallback };
  }
  const relink = error.code === "paypal-account-not-linked" || error.code === "member-not-found";
  const known = MESSAGES[error.code];
  if (BACKEND_SENTENCE.has(error.code) && error.detail) {
    return { message: error.detail };
  }
  if (known) {
    return { message: known, ...(relink ? { relink } : {}) };
  }
  return { message: error.status >= 500 ? "Something went wrong on our side. Nothing was saved. Please try again." : fallback };
}
