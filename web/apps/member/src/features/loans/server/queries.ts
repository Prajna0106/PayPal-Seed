import "server-only";

import {
  ApiProblem,
  type HardshipView,
  type InstalmentLine,
  type LoanSummary,
  type ScheduleView,
  type TransactionView,
  unwrap,
  unwrapOrNull,
} from "@paypal-seed/api-client";
import { isTimelinePayload, type TimelinePayload } from "@paypal-seed/timeline/types";
import { notFound } from "next/navigation";
import { memberApi } from "@/server/api";
import { type Instalment, type InstalmentStatus, isSettled, type Plan, type Transaction } from "../model";

export interface LoanCardModel {
  loanId: string;
  status: string;
  principalCents: number;
  outstandingCents: number;
  autopay: boolean;
  plan: Plan | null;
  /** An open hardship case: an offer to look at, or a choice being reviewed. */
  help: "offer" | "reviewing" | null;
}

/** Everything the member home shows: each loan with its plan and whether help is waiting, read in parallel. */
export async function getMemberHome(): Promise<LoanCardModel[]> {
  const api = await memberApi();
  const loans = await unwrap(api.GET("/api/v1/loans"));
  return Promise.all(
    loans.filter((loan): loan is LoanSummary & { loanId: string } => Boolean(loan.loanId)).map(async (loan) => {
      const active = loan.status === "ACTIVE";
      const [schedule, hardship] = await Promise.all([
        unwrapOrNull(api.GET("/api/v1/loans/{loanId}/schedule", { params: { path: { loanId: loan.loanId } } })),
        active
          ? unwrapOrNull(api.GET("/api/v1/loans/{loanId}/hardship", { params: { path: { loanId: loan.loanId } } }))
          : Promise.resolve(null),
      ]);
      return {
        loanId: loan.loanId,
        status: loan.status ?? "ACTIVE",
        principalCents: loan.principalCents ?? 0,
        outstandingCents: loan.outstandingCents ?? 0,
        autopay: loan.collectionMode === "AUTOPAY",
        plan: schedule ? toPlan(schedule) : null,
        help: helpState(hardship),
      };
    }),
  );
}

export interface LoanDetail {
  plan: Plan;
  timeline: TimelinePayload | null;
  transactions: Transaction[];
  payoffCents: number;
  help: "offer" | "reviewing" | null;
}

/** The loan page: plan, timeline, money history, payoff quote and help state, fetched together. */
export async function getLoanDetail(loanId: string): Promise<LoanDetail> {
  const api = await memberApi();
  const path = { params: { path: { loanId } } };
  try {
    const [schedule, timeline, transactions, payoff, hardship] = await Promise.all([
      unwrap(api.GET("/api/v1/loans/{loanId}/schedule", path)),
      unwrapOrNull(api.GET("/api/v1/loans/{loanId}/timeline", path)),
      unwrap(api.GET("/api/v1/loans/{loanId}/transactions", path)),
      unwrap(api.GET("/api/v1/loans/{loanId}/payoff-quote", path)),
      unwrapOrNull(api.GET("/api/v1/loans/{loanId}/hardship", path)),
    ]);
    return {
      plan: toPlan(schedule),
      timeline: isTimelinePayload(timeline) ? timeline : null,
      transactions: transactions.map(toTransaction),
      payoffCents: payoff.outstandingCents,
      help: helpState(hardship),
    };
  } catch (error) {
    // Another member's loan and a missing loan both answer 404 (the backend never confirms a loan exists).
    if (error instanceof ApiProblem && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

/** The current plan only (the help page needs the next instalment and the plan reason). */
export async function getPlan(loanId: string): Promise<Plan> {
  const api = await memberApi();
  try {
    return toPlan(await unwrap(api.GET("/api/v1/loans/{loanId}/schedule", { params: { path: { loanId } } })));
  } catch (error) {
    if (error instanceof ApiProblem && error.status === 404) {
      notFound();
    }
    throw error;
  }
}

function helpState(view: HardshipView | null): LoanCardModel["help"] {
  if (!view) {
    return null;
  }
  return view.offer ? "offer" : "reviewing";
}

export function toPlan(schedule: ScheduleView): Plan {
  const instalments = (schedule.instalments ?? []).map(toInstalment);
  return {
    loanId: schedule.loanId ?? "",
    version: schedule.version ?? 1,
    reason: schedule.reason ?? "ORIGINAL",
    businessDate: schedule.businessDate ?? "",
    principalCents: schedule.principalCents ?? 0,
    outstandingCents: schedule.outstandingCents ?? 0,
    overpaidCents: schedule.overpaidCents ?? 0,
    loanStatus: schedule.loanStatus ?? "ACTIVE",
    autopay: schedule.collectionMode === "AUTOPAY",
    instalments,
    next: instalments.find((i) => i.remainingCents > 0) ?? null,
    paidCount: instalments.filter((i) => isSettled(i.status)).length,
  };
}

function toInstalment(line: InstalmentLine): Instalment {
  return {
    seq: line.seq ?? 0,
    dueDate: line.dueDate ?? "",
    amountCents: line.amountCents ?? 0,
    paidCents: line.paidCents ?? 0,
    remainingCents: line.remainingCents ?? 0,
    status: (line.status ?? "SCHEDULED") as InstalmentStatus,
    paidOn: line.paidOn ?? null,
  };
}

function toTransaction(t: TransactionView): Transaction {
  return {
    id: t.id ?? `${t.paypalTxnId ?? ""}-${t.effectiveDate ?? ""}`,
    type: t.type ?? "RECEIPT",
    source: t.source ?? "PAYPAL",
    amountCents: t.amountCents ?? 0,
    effectiveDate: t.effectiveDate ?? "",
  };
}
