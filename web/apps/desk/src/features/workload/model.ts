import type { WorkloadBar, WorkloadRow, WorkloadSla } from "@paypal-seed/timeline";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import type { Workload } from "./server/queries";

type Item = Workload["items"][number];
type Role = Item["role"];
type Kind = Item["kind"];

export const ROLE_NAMES: Record<Role, string> = { OFFICER: "Officer", CHECKER: "Checker" };
export const KIND_NAMES: Record<Kind, string> = {
  LOAN_APPLICATION: "Loan applications",
  HARDSHIP_DECISION: "Hardship decisions",
};

export const SLA_LABELS: Record<WorkloadSla, string> = {
  OVERDUE: "Overdue",
  DUE_SOON: "Due soon",
  ON_TRACK: "On track",
  NO_SLA: "No deadline",
};

export const SLA_TONES: Record<WorkloadSla, StatusTone> = {
  OVERDUE: "danger",
  DUE_SOON: "warning",
  ON_TRACK: "success",
  NO_SLA: "neutral",
};

const HOUR_MS = 3_600_000;
const ROLES: Role[] = ["OFFICER", "CHECKER"];
const KINDS: Kind[] = ["LOAN_APPLICATION", "HARDSHIP_DECISION"];

const rowId = (role: Role, kind: Kind) => `${role}:${kind}`;

/** Every role and kind gets a row, even with nothing waiting, so the chart keeps one shape. */
export function workloadRows(): WorkloadRow[] {
  return ROLES.flatMap((role) =>
    KINDS.map((kind) => ({ id: rowId(role, kind), name: `${ROLE_NAMES[role]} · ${KIND_NAMES[kind]}` })),
  );
}

const plural = (n: number) => `${n.toLocaleString("en-US")} waiting`;

/**
 * Where each bucket is drawn: overdue runs back from now to its oldest deadline, the rest run forward from now to
 * theirs. Only the drawing uses these times; every count and state shown is the backend's.
 */
export function workloadBars(data: Workload): WorkloadBar[] {
  const now = new Date(data.asOf).getTime();
  return data.items
    .filter((item) => item.waiting > 0)
    .map((item) => {
      const due = item.earliestSlaDueAt ? new Date(item.earliestSlaDueAt).getTime() : undefined;
      let start = now;
      let end = now + HOUR_MS;
      if (item.slaBucket === "OVERDUE") {
        end = now;
        start = due !== undefined && due < now ? due : now - 4 * HOUR_MS;
      } else if (item.slaBucket === "DUE_SOON") {
        end = due !== undefined && due > now ? due : now + 4 * HOUR_MS;
      } else if (item.slaBucket === "ON_TRACK") {
        end = due !== undefined && due > now ? due : now + 24 * HOUR_MS;
      }
      return {
        id: `${rowId(item.role, item.kind)}:${item.slaBucket}`,
        rowId: rowId(item.role, item.kind),
        sla: item.slaBucket,
        start: new Date(start).toISOString(),
        end: new Date(end).toISOString(),
        label: plural(item.waiting),
        detail: item.earliestSlaDueAt ? `Oldest deadline ${formatDateTime(item.earliestSlaDueAt)}` : "No deadline set",
      };
    });
}
