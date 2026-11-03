import "server-only";

import { type ApiProblem, isProblem, problemMessage } from "@paypal-seed/api-client";

/** Codes the loan desk and the fair-check desk can raise, in plain words. The shared map covers the rest. */
const MESSAGES: Record<string, string> = {
  "maker-checker-same-officer": "A different officer must sign this one.",
  "sod-violation": "A different officer must sign this one.",
  "application-invalid-state":
    "This application changed since you opened it. It has been reloaded: check where it stands, then try again.",
  "payout-recipient-missing": "The member has no PayPal account on file to pay out to.",
  "dependency-unavailable": "PayPal could not be reached. Nothing changed. Try again in a moment.",
  "payout-not-found": "There is no payout to check yet for this application.",
  "paypal-rejected": "PayPal did not answer that check. Nothing changed. Try again in a moment.",
  "assessment-not-found": "That fair check could not be found.",
  "invalid-officer-decision": "That decision is not valid. Check the amount, the term and the reason.",
  "desk-query-invalid-cursor": "The list changed while you were scrolling. Refresh it to start again.",
  "desk-query-invalid-sort": "That sort is not available.",
  "desk-query-invalid-filter": "That filter is not available.",
  "desk-query-invalid-limit": "That page size is not available.",
};

/** A sentence an officer can act on; never a stack trace, a raw code or the backend's JSON. */
export function describeProblem(error: ApiProblem): string {
  const known = MESSAGES[error.code];
  if (known) {
    return known;
  }
  if (error.status === 401) {
    return "Please sign in again.";
  }
  if (error.status === 403) {
    return "Your role cannot do this.";
  }
  if (error.status >= 500) {
    return "The Seed backend had a problem. Nothing was changed. Try again in a moment.";
  }
  return problemMessage(error);
}

/** The state of the thing changed under the officer, so the page should re-read before they try again. */
export function isStale(error: unknown): boolean {
  return isProblem(error) && (error.status === 409 || error.code === "application-invalid-state");
}
