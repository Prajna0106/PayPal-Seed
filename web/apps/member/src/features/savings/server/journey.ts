import "server-only";

import { unwrap, unwrapOrNull } from "@paypal-seed/api-client";
import { formatMoney, parseBusinessDate } from "@paypal-seed/ui/lib/format";
import { isTimelinePayload } from "@paypal-seed/timeline/types";
import type { JourneyEvent, JourneyPayload, JourneyRow } from "@paypal-seed/timeline";
import { memberApi } from "@/server/api";
import { requireSession } from "@/server/session";
import { TIMELINE_STATUS_LABELS } from "@/features/loans/status";
import { dateInProgramZone, GOAL_STATUS, type Goal, toGoal } from "../model";
import { readHome } from "./queries";

export interface JourneyModel {
  payload: JourneyPayload;
  /** The business date: the "today" line. */
  today: string;
  /** Plain-language reasons the loan is not open yet, from the lending module. Empty when it is open. */
  reasons: string[];
  loanOpen: boolean;
}

const SAVING_ROW = "saving";
const UNLOCK_ROW = "unlock";
const DAY_MS = 86_400_000;

/** `YYYY-MM-DD` plus whole days. A business date has no zone, so the sum is done in UTC. */
function addDays(iso: string, days: number): string {
  return new Date(parseBusinessDate(iso).getTime() + days * DAY_MS).toISOString().slice(0, 10);
}

const GOAL_COLOUR: Partial<Record<Goal["status"], string>> = { ACTIVE: "green", PAUSED: "gray", COMPLETED: "teal" };

/**
 * One timeline from the first goal to the loan: the member's goals (from the day each started), the day a loan opens
 * (only when lending says it is open, never guessed) and the instalments the loan timeline already colours. The
 * deposits themselves have no list in the contract, so the saving bars run from the day the goal started.
 */
export async function getJourney(): Promise<JourneyModel> {
  const [session, api] = await Promise.all([requireSession(), memberApi()]);
  const [goals, home, loans] = await Promise.all([
    unwrap(api.GET("/api/v1/savings/members/{memberId}/goals", { params: { path: { memberId: session.memberId } } })),
    readHome(),
    unwrap(api.GET("/api/v1/loans")),
  ]);

  // Without the home summary the wall clock stands in for the business date.
  const today = home?.generatedOn ?? dateInProgramZone(new Date().toISOString());
  const rows: JourneyRow[] = [];
  const events: JourneyEvent[] = [];

  const saving = goals.map(toGoal).filter((goal) => GOAL_COLOUR[goal.status] !== undefined);
  if (saving.length > 0) {
    rows.push({ id: SAVING_ROW, name: "Saving" });
  }
  for (const goal of saving) {
    const start = dateInProgramZone(goal.createdAt);
    events.push({
      id: `goal-${goal.id}`,
      rowId: SAVING_ROW,
      startDate: start,
      endDate: today > start ? today : addDays(start, 1),
      name: goal.name,
      detail: `${GOAL_STATUS[goal.status].label}. ${formatMoney(goal.savedCents)} saved so far, ${formatMoney(goal.monthlyCents)} a month.`,
      eventColor: GOAL_COLOUR[goal.status] ?? "gray",
    });
    if (goal.targetDate) {
      events.push({
        id: `target-${goal.id}`,
        rowId: SAVING_ROW,
        startDate: goal.targetDate,
        endDate: addDays(goal.targetDate, 1),
        name: "Target",
        detail: `Your target of ${formatMoney(goal.targetCents)} for ${goal.name}.`,
        eventColor: "blue",
      });
    }
  }

  const eligibility = home?.lending?.eligibility;
  const loanOpen = eligibility?.eligible === true;
  if (eligibility && loanOpen) {
    rows.push({ id: UNLOCK_ROW, name: "Loan opens" });
    events.push({
      id: "unlock",
      rowId: UNLOCK_ROW,
      startDate: today,
      endDate: addDays(today, 1),
      name: "Loan open",
      detail: `You can ask for a loan of up to ${formatMoney(eligibility.maxLoan.cents)}.`,
      eventColor: "green",
    });
  }

  const timelines = await Promise.all(
    loans
      .filter((loan): loan is typeof loan & { loanId: string } => Boolean(loan.loanId) && loan.status !== "CANCELLED")
      .map(async (loan) => ({
        loanId: loan.loanId,
        timeline: await unwrapOrNull(api.GET("/api/v1/loans/{loanId}/timeline", { params: { path: { loanId: loan.loanId } } })),
      })),
  );
  for (const { loanId, timeline } of timelines) {
    if (!isTimelinePayload(timeline)) {
      continue;
    }
    const rowId = `loan-${loanId}`;
    rows.push({ id: rowId, name: timeline.resources[0]?.name ?? "Your loan" });
    for (const event of timeline.events) {
      events.push({
        id: event.id,
        rowId,
        startDate: event.startDate,
        endDate: event.endDate,
        name: event.name,
        detail: `Loan payment: ${TIMELINE_STATUS_LABELS[event.status] ?? event.status}.`,
        eventColor: event.eventColor,
      });
    }
  }

  return { payload: { rows, events }, today, reasons: loanOpen ? [] : (eligibility?.reasons ?? []), loanOpen };
}
