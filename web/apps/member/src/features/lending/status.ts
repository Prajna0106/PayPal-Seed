import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import type { ApplicationStatus } from "./model";

/** Plain words for each application status. A decline is neutral, never red: it is not a failure of the member. */
export const APPLICATION_STATUS: Record<ApplicationStatus, { label: string; tone: StatusTone; note: string }> = {
  SUBMITTED: {
    label: "Being reviewed",
    tone: "info",
    note: "A person at Seed is looking at your application. We will tell you as soon as there is news.",
  },
  PENDING_SECOND_APPROVAL: {
    label: "Being reviewed",
    tone: "info",
    note: "A person at Seed is looking at your application. We will tell you as soon as there is news.",
  },
  APPROVED: {
    label: "Approved",
    tone: "success",
    note: "Good news: your loan is approved. We are getting the money ready to send.",
  },
  DISBURSING: {
    label: "Sending the money",
    tone: "info",
    note: "The money is on its way to you through PayPal. It can take a little while to arrive.",
  },
  DISBURSED: {
    label: "Received",
    tone: "success",
    note: "The money reached you, and your repayment plan is ready.",
  },
  REJECTED: {
    label: "Not approved",
    tone: "neutral",
    note: "We could not approve this one right now. That is not a judgement on you.",
  },
  CANCELLED_COOLING_OFF: {
    label: "Returned",
    tone: "neutral",
    note: "You returned this loan. Nothing is owed and nothing was charged.",
  },
};

export type StepState = "done" | "current" | "upcoming" | "stopped";

export interface TimelineStep {
  key: string;
  label: string;
  state: StepState;
}

const PATH = [
  { key: "submitted", label: "Submitted" },
  { key: "review", label: "Being reviewed" },
  { key: "approved", label: "Approved" },
  { key: "sending", label: "Sending the money" },
  { key: "received", label: "Received" },
] as const;

/** Which step of the normal path each status sits on (index into {@link PATH}). */
const POSITION: Record<ApplicationStatus, number> = {
  SUBMITTED: 1,
  PENDING_SECOND_APPROVAL: 1,
  APPROVED: 2,
  DISBURSING: 3,
  DISBURSED: 4,
  REJECTED: 1,
  CANCELLED_COOLING_OFF: 4,
};

/** The steps a member sees, with where they are. A decline or a return ends the path with one quiet final step. */
export function applicationTimeline(status: ApplicationStatus): TimelineStep[] {
  const at = POSITION[status];
  const ended = status === "REJECTED" || status === "CANCELLED_COOLING_OFF";
  const steps: TimelineStep[] = PATH.map((step, index) => ({
    key: step.key,
    label: step.label,
    state: ended
      ? index <= at
        ? "done"
        : "stopped"
      : index < at
        ? "done"
        : index === at
          ? status === "DISBURSED"
            ? "done"
            : "current"
          : "upcoming",
  }));
  if (status === "REJECTED") {
    return [...steps.slice(0, 2), { key: "declined", label: "Not approved this time", state: "stopped" }];
  }
  if (status === "CANCELLED_COOLING_OFF") {
    return [...steps, { key: "returned", label: "Returned", state: "stopped" }];
  }
  return steps;
}
