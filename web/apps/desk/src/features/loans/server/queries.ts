import "server-only";

import { ApiProblem, identityHeaders, unwrap } from "@paypal-seed/api-client";
import type { ScheduleView, VersionView } from "@paypal-seed/api-client/types";
import type { TimelineEvent, TimelinePayload } from "@paypal-seed/timeline";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { staffApi } from "@/server/api";
import { env } from "@/server/env";
import { requireSession } from "@/server/session";

export async function deskSchedule(loanId: string): Promise<ScheduleView> {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/loans/{loanId}/schedule", { params: { path: { loanId } } }));
}

export async function deskIntegrity(loanId: string) {
  const api = await staffApi();
  return unwrap(api.GET("/api/v1/desk/loans/{loanId}/integrity", { params: { path: { loanId } } }));
}

/**
 * Every schedule version with the backend's diff from the one before. The backend serves this at
 * `GET /api/v1/desk/loans/{loanId}/schedule/versions`, but `seed-api.yaml` does not declare that desk path yet, so
 * it is read here with the same staff identity instead of the typed client.
 */
export async function deskVersions(loanId: string): Promise<VersionView[]> {
  const session = await requireSession();
  const response = await fetch(`${env.SEED_API_URL}/api/v1/desk/loans/${encodeURIComponent(loanId)}/schedule/versions`, {
    headers: identityHeaders({ kind: "staff", staffId: session.staffId, roles: session.roles }),
    cache: "no-store",
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    throw new ApiProblem(response.status, (await response.json().catch(() => undefined)) as never);
  }
  return (await response.json()) as VersionView[];
}

/** Same colours the backend uses for the member timeline (`TimelineView.java`), keyed by instalment status. */
const COLOURS: Record<string, string> = {
  PAID: "green",
  PAID_LATE: "orange",
  PREPAID: "teal",
  DUE: "blue",
  SCHEDULED: "gray",
  PARTIALLY_PAID: "cyan",
  IN_GRACE: "amber",
  LATE: "red",
};

const GRACE_DAYS = 5;

function nextDay(iso: string, days = 1): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y ?? 1970, (m ?? 1) - 1, (d ?? 1) + days)).toISOString().slice(0, 10);
}

/**
 * The Bryntum payload for the desk, shaped exactly like the member timeline the backend builds: the current plan,
 * the previous plan's moved payments as ghosts with arrows to where they went (from the backend's version diff),
 * and grace windows. Nothing is computed beyond placing the backend's dates on the chart.
 */
export function deskTimeline(loanId: string, current: ScheduleView, versions: VersionView[]): TimelinePayload {
  const resourceId = `loan-${loanId}`;
  const version = `v${current.version ?? 1}`;
  const events: TimelineEvent[] = (current.instalments ?? []).map((line) => ({
    id: `${version}-s${line.seq}`,
    resourceId,
    startDate: line.dueDate ?? "",
    endDate: nextDay(line.dueDate ?? ""),
    name: formatMoney(line.amountCents ?? 0),
    status: line.status ?? "SCHEDULED",
    eventColor: COLOURS[line.status ?? ""] ?? "gray",
    readOnly: true,
  }));
  const timeRanges = (current.instalments ?? [])
    .filter((line) => (line.remainingCents ?? 0) > 0 && line.dueDate)
    .map((line) => ({
      id: `grace-${version}-s${line.seq}`,
      startDate: nextDay(line.dueDate ?? ""),
      endDate: nextDay(line.dueDate ?? "", GRACE_DAYS + 1),
      name: "Grace",
    }));
  const diff = versions.find((v) => v.version === current.version)?.diffFromPrevious;
  const previous = `v${(current.version ?? 1) - 1}`;
  const removed = diff?.removed ?? [];
  const added = diff?.added ?? [];
  const ghostEvents: TimelineEvent[] = removed.map((slot) => ({
    id: `${previous}-s${slot.seq}`,
    resourceId,
    startDate: slot.dueDate ?? "",
    endDate: nextDay(slot.dueDate ?? ""),
    name: formatMoney(slot.amountCents ?? 0),
    status: "PREVIOUS_PLAN",
    eventColor: "gray",
    readOnly: true,
  }));
  const dependencies = removed.flatMap((slot, i) => {
    const target = added[i];
    return target ? [{ from: `${previous}-s${slot.seq}`, to: `${version}-s${target.seq}`, cls: "shift" }] : [];
  });
  return {
    resources: [{ id: resourceId, name: `Loan · ${formatMoney(current.principalCents ?? 0)}` }],
    events,
    ghostEvents,
    dependencies,
    timeRanges,
  };
}
