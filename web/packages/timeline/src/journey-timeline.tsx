"use client";

import "@bryntum/schedulerpro/schedulerpro.css";
import "@bryntum/schedulerpro/svalbard-light.css";
import "@bryntum/schedulerpro/fontawesome/css/fontawesome.css";
import "@bryntum/schedulerpro/fontawesome/css/solid.css";
import "./timeline.css";
import "./journey.css";

import { StringHelper } from "@bryntum/schedulerpro";
import { BryntumSchedulerPro, type BryntumSchedulerProProps } from "@bryntum/schedulerpro-react";
import { useMemo, useSyncExternalStore } from "react";

/** One row of the journey, such as "Saving" or "Loan payments". */
export interface JourneyRow {
  id: string;
  name: string;
}

/** One bar or marker. Dates are business dates (`YYYY-MM-DD`); the end date is exclusive, as in Bryntum. */
export interface JourneyEvent {
  id: string;
  rowId: string;
  startDate: string;
  endDate: string;
  /** Short text drawn on the bar. */
  name: string;
  /** A sentence in plain words for the tooltip and the screen-reader list. */
  detail: string;
  /** A Bryntum colour name (`green`, `gray`, `blue`...). */
  eventColor: string;
}

export interface JourneyPayload {
  rows: JourneyRow[];
  events: JourneyEvent[];
}

export interface JourneyLabels {
  /** Accessible name of the whole timeline. */
  title: string;
  today: string;
  /** Formats a business date for the tooltip and the screen-reader list. */
  formatDate: (isoDate: string) => string;
}

export interface JourneyTimelineProps {
  payload: JourneyPayload;
  /** The business date (`YYYY-MM-DD`): the "today" line follows the demo clock, not the wall clock. */
  today: string;
  labels: JourneyLabels;
  /** Height of the chart area in pixels. The component needs a real height to render. */
  height?: number;
  /**
   * Adds the events as a list for screen readers under the chart. Turn it off when the page already shows the same
   * events as a list, so they are not read twice.
   */
  listForScreenReaders?: boolean;
  className?: string;
}

const DAY_MS = 86_400_000;

/**
 * Bryntum Scheduler Pro, read-only: a member's whole journey on one axis, from the first deposit through the day a
 * loan opens to the loan's instalments, each kind of step on its own row with a line for the business date. The chart
 * is hidden from assistive technology; a list of the same events stands in for it.
 */
export function JourneyTimeline({ payload, today, labels, height = 280, listForScreenReaders = true, className }: JourneyTimelineProps) {
  const reducedMotion = useReducedMotion();
  const config = useMemo(() => buildConfig(payload, today, labels), [payload, today, labels]);
  const sorted = useMemo(() => [...payload.events].sort((a, b) => a.startDate.localeCompare(b.startDate)), [payload.events]);

  return (
    <figure className={["seed-timeline", "seed-journey", className].filter(Boolean).join(" ")} aria-label={labels.title}>
      <div className="seed-timeline__chart" style={{ height }} aria-hidden="true">
        <BryntumSchedulerPro {...config} useInitialAnimation={reducedMotion ? false : "fade-in"} />
      </div>
      {listForScreenReaders ? (
        <figcaption className="sr-only">
          <p>{labels.title}</p>
          <ol>
            {sorted.map((event) => (
              <li key={event.id}>
                {labels.formatDate(event.startDate)}: {event.name}. {event.detail}
              </li>
            ))}
          </ol>
        </figcaption>
      ) : null}
    </figure>
  );
}

function buildConfig(payload: JourneyPayload, today: string, labels: JourneyLabels): BryntumSchedulerProProps {
  const events = payload.events.map((event) => ({
    id: event.id,
    resourceId: event.rowId,
    startDate: event.startDate,
    endDate: event.endDate,
    name: event.name,
    eventColor: event.eventColor,
    eventStyle: "tonal" as const,
    readOnly: true,
    draggable: false,
    resizable: false,
    // Kept for the tooltip.
    detail: event.detail,
    startIso: event.startDate,
  }));
  const { start, end } = visibleRange([...payload.events.flatMap((event) => [event.startDate, event.endDate]), today]);

  return {
    readOnly: true,
    resources: payload.rows,
    events,
    timeRanges: [{ id: "today", startDate: today, name: labels.today, cls: "seed-today" }],
    startDate: start,
    endDate: end,
    viewPreset: "monthAndYear",
    tickSize: 96,
    rowHeight: 64,
    barMargin: 14,
    eventLayout: "none",
    columns: [{ type: "resourceInfo", text: "", field: "name", width: 140, showImage: false, sortable: false }],
    eventRenderer: ({ eventRecord }) => StringHelper.encodeHtml(String(eventRecord.name ?? "")),
    // Read-only everywhere: no drag, resize, create, edit or context menus.
    eventDragFeature: false,
    eventResizeFeature: false,
    eventDragCreateFeature: false,
    eventEditFeature: false,
    eventMenuFeature: false,
    scheduleMenuFeature: false,
    cellMenuFeature: false,
    scheduleTooltipFeature: false,
    timeRangesFeature: { showHeaderElements: true },
    eventTooltipFeature: {
      template: ({ eventRecord }: { eventRecord: { name?: unknown; getData?: (field: string) => unknown } }) => {
        const iso = String(eventRecord.getData?.("startIso") ?? "");
        const detail = String(eventRecord.getData?.("detail") ?? "");
        return `<strong>${StringHelper.encodeHtml(String(eventRecord.name ?? ""))}</strong>`
          + `<br>${StringHelper.encodeHtml(iso ? labels.formatDate(iso) : "")}`
          + `<br>${StringHelper.encodeHtml(detail)}`;
      },
    },
    zoomOnMouseWheel: false,
    zoomOnTimeAxisDoubleClick: false,
    enableEventAnimations: false,
    ariaLabel: labels.title,
  };
}

/** Pads the journey with a little air on both sides so the first and last steps are not on the edge. */
function visibleRange(isoDates: string[]): { start: Date; end: Date } {
  const times = isoDates.map(toLocalTime).filter((t) => Number.isFinite(t));
  const first = times.length > 0 ? Math.min(...times) : Date.now();
  const last = times.length > 0 ? Math.max(...times) : Date.now();
  return { start: new Date(first - 30 * DAY_MS), end: new Date(last + 45 * DAY_MS) };
}

/** Business dates have no zone; Bryntum draws in local time, so read them as local midnight. */
function toLocalTime(iso: string): number {
  const [y, m, d] = iso.split("-").map(Number);
  return y && m && d ? new Date(y, m - 1, d).getTime() : Number.NaN;
}

function subscribeReducedMotion(onChange: () => void): () => void {
  const query = window.matchMedia("(prefers-reduced-motion: reduce)");
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

function useReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    () => true,
  );
}
