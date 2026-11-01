import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { CobRun, HardshipCaseStatus, HardshipQueueRow, KillSwitchState } from "@paypal-seed/api-client/types";
import { cache } from "react";
import { CASE_STATUSES, STATUS_ORDER } from "@/features/hardship/model";
import { approvalStage, caseDetail, queuePage } from "@/features/hardship/server/queries";
import { staffApi } from "@/server/api";

/** The overview reads the newest cases; a desk with more than this many open cases filters the queue instead. */
const RECENT_CASES = 200;
/** Approval stages need each pending case's detail; cap the fan-out so the overview stays fast. */
const MAX_PENDING_DETAILS = 30;

export const recentCases = cache(async (): Promise<HardshipQueueRow[]> => {
  const page = await queuePage({ status: [], level: [], origin: [] }, undefined, RECENT_CASES);
  return page.rows;
});

export interface DeskKpis {
  open: number;
  waitingForMaker: number;
  waitingForChecker: number;
  failed: number;
  pendingSampled: boolean;
}

export async function deskKpis(): Promise<DeskKpis> {
  const rows = await recentCases();
  const pending = rows.filter((row) => row.status === "PENDING_APPROVAL");
  const sampled = pending.slice(0, MAX_PENDING_DETAILS);
  const stages = await Promise.all(
    sampled.map((row) =>
      row.caseId ? caseDetail(row.caseId).then((detail) => approvalStage(detail.approval)) : Promise.resolve("NONE" as const),
    ),
  );
  return {
    open: rows.filter((row) => row.status && CASE_STATUSES[row.status].open).length,
    waitingForMaker: stages.filter((s) => s === "WAITING_FOR_MAKER").length,
    waitingForChecker: stages.filter((s) => s === "WAITING_FOR_CHECKER").length,
    failed: rows.filter((row) => row.status === "APPROVED_EXECUTION_FAILED").length,
    pendingSampled: pending.length > sampled.length,
  };
}

export interface StatusCount {
  status: HardshipCaseStatus;
  count: number;
}

export async function casesByStatus(): Promise<StatusCount[]> {
  const rows = await recentCases();
  const counts = new Map<HardshipCaseStatus, number>();
  for (const row of rows) {
    if (row.status) {
      counts.set(row.status, (counts.get(row.status) ?? 0) + 1);
    }
  }
  return STATUS_ORDER.filter((status) => counts.has(status)).map((status) => ({ status, count: counts.get(status) ?? 0 }));
}

/** Cases waiting on an officer, oldest first: the desk's to-do list. */
export async function needsAttention(limit = 5): Promise<HardshipQueueRow[]> {
  const rows = await recentCases();
  return rows
    .filter((row) => row.status === "PENDING_APPROVAL" || row.status === "APPROVED_EXECUTION_FAILED")
    .sort((a, b) => (a.openedOn ?? "").localeCompare(b.openedOn ?? ""))
    .slice(0, limit);
}

export async function agentState(): Promise<KillSwitchState> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/agents/kill-switch", { params: { query: { agent: "guardian" } } }));
}

/** Close-of-business runs, newest first. The demo clock can sit years ahead of the wall clock, so the range is wide. */
export async function recentCobRuns(limit = 7): Promise<CobRun[]> {
  const api = await staffApi();
  const runs = await unwrap(api.GET("/api/v1/desk/cob-runs", { params: { query: { from: "2000-01-01", to: "2099-12-31" } } }));
  return [...runs].sort((a, b) => (b.businessDate ?? "").localeCompare(a.businessDate ?? "")).slice(0, limit);
}
