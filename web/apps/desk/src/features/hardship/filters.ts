import type { HardshipCaseStatus } from "@paypal-seed/api-client/types";
import { CASE_STATUSES, type CaseOrigin, ORIGINS, RISK_LEVELS, type RiskLevel, STATUS_ORDER } from "./model";

/**
 * Queue filters live in the URL (`?status=open&level=LIKELY_MISS`), so a filtered queue is a link an officer can
 * share. `status` also accepts the presets below, which expand to the statuses the backend understands.
 */
export const STATUS_PRESETS = {
  open: { label: "Open cases", statuses: STATUS_ORDER.filter((s) => CASE_STATUSES[s].open) },
  closed: { label: "Closed cases", statuses: STATUS_ORDER.filter((s) => !CASE_STATUSES[s].open) },
  all: { label: "All cases", statuses: [] as HardshipCaseStatus[] },
} as const;

export type StatusPreset = keyof typeof STATUS_PRESETS;

export interface QueueSearch {
  status: string;
  level: string;
  origin: string;
}

const isStatus = (value: string): value is HardshipCaseStatus => value in CASE_STATUSES;
const isLevel = (value: string): value is RiskLevel => value in RISK_LEVELS;
const isOrigin = (value: string): value is CaseOrigin => value in ORIGINS;

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** Reads the URL into a normalised search; anything unknown falls back to "open cases, any level, any origin". */
export function readQueueSearch(params: Record<string, string | string[] | undefined>): QueueSearch {
  const status = first(params.status) ?? "open";
  const level = first(params.level) ?? "any";
  const origin = first(params.origin) ?? "any";
  return {
    status: status in STATUS_PRESETS || isStatus(status) ? status : "open",
    level: isLevel(level) ? level : "any",
    origin: isOrigin(origin) ? origin : "any",
  };
}

/** The query string the queue route (and the backend) expects for a search. */
export function queueQuery(search: QueueSearch): URLSearchParams {
  const query = new URLSearchParams();
  const statuses: readonly HardshipCaseStatus[] =
    search.status in STATUS_PRESETS
      ? STATUS_PRESETS[search.status as StatusPreset].statuses
      : isStatus(search.status)
        ? [search.status]
        : [];
  for (const status of statuses) {
    query.append("status", status);
  }
  if (isLevel(search.level)) {
    query.append("level", search.level);
  }
  if (isOrigin(search.origin)) {
    query.append("origin", search.origin);
  }
  return query;
}
