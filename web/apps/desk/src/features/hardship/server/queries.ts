import "server-only";

import { unwrap } from "@paypal-seed/api-client";
import type { HardshipCaseDetail, HardshipCaseStatus, HardshipQueueRow } from "@paypal-seed/api-client/types";
import { staffApi } from "@/server/api";
import type { CaseOrigin, RiskLevel } from "../model";

export interface QueueFilters {
  status: HardshipCaseStatus[];
  level: RiskLevel[];
  origin: CaseOrigin[];
}

export interface QueuePage {
  rows: HardshipQueueRow[];
  nextBefore: string | null;
}

/** One keyset page of the desk queue, newest case first. */
export async function queuePage(filters: QueueFilters, before: string | undefined, limit: number): Promise<QueuePage> {
  const api = await staffApi();
  const page = await unwrap(
    api.GET("/api/v1/desk/hardship/cases", {
      params: { query: { ...filters, ...(before ? { before } : {}), limit } },
    }),
  );
  return { rows: page.rows ?? [], nextBefore: page.nextBefore ?? null };
}

export async function caseDetail(caseId: string): Promise<HardshipCaseDetail> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/hardship/cases/{caseId}", { params: { path: { caseId } } }));
}

export type Approval = NonNullable<HardshipCaseDetail["approval"]>;

export type ApprovalStage = "WAITING_FOR_MAKER" | "WAITING_FOR_CHECKER" | "DECIDED" | "NONE";

/** Who the open approval request is waiting for, read from the decisions governance recorded. */
export function approvalStage(approval: Approval | null | undefined): ApprovalStage {
  if (!approval) {
    return "NONE";
  }
  if (approval.status !== "OPEN") {
    return "DECIDED";
  }
  const roles = new Set((approval.decisions ?? []).map((d) => d.role));
  return roles.has("MAKER") && approval.checkerRequired !== false ? "WAITING_FOR_CHECKER" : "WAITING_FOR_MAKER";
}
