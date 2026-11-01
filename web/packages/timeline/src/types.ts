/**
 * The Bryntum payload the backend builds for `GET /api/v1/loans/{loanId}/timeline` (`TimelineView.java`). The
 * contract describes these lists only loosely, so the shape is narrowed here; every value still comes from the
 * backend, including the colour of each instalment.
 */
export interface TimelineResource {
  id: string;
  name: string;
}

export interface TimelineEvent {
  id: string;
  resourceId: string;
  /** Business date, `YYYY-MM-DD`. */
  startDate: string;
  endDate: string;
  /** The amount, already formatted by the backend (`$150`). */
  name: string;
  /** Instalment status (`PAID`, `DUE`, ...) or `PREVIOUS_PLAN` for a ghost. */
  status: string;
  eventColor: string;
  readOnly: boolean;
}

export interface TimelineDependency {
  from: string;
  to: string;
  cls?: string;
}

export interface TimelineRange {
  id: string;
  startDate: string;
  endDate: string;
  name: string;
}

export interface TimelinePayload {
  resources: TimelineResource[];
  events: TimelineEvent[];
  ghostEvents: TimelineEvent[];
  dependencies: TimelineDependency[];
  timeRanges: TimelineRange[];
}

/** Words the timeline shows. The app passes them in its own language. */
export interface TimelineLabels {
  /** Accessible name of the whole timeline. */
  title: string;
  currentPlan: string;
  previousPlan: string;
  today: string;
  /** Instalment status → plain words (`PAID` → "Paid"). Unknown statuses fall back to the code. */
  status: Record<string, string>;
  /** Formats a business date for the tooltip and the screen-reader list. */
  formatDate: (isoDate: string) => string;
}

/** `true` when the value looks like the backend's timeline payload. */
export function isTimelinePayload(value: unknown): value is TimelinePayload {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return Array.isArray(v.resources) && Array.isArray(v.events) && Array.isArray(v.ghostEvents)
    && Array.isArray(v.dependencies) && Array.isArray(v.timeRanges);
}
