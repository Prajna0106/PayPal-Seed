"use client";

import "@bryntum/schedulerpro/schedulerpro.css";
import "@bryntum/schedulerpro/svalbard-light.css";
import "@bryntum/schedulerpro/fontawesome/css/fontawesome.css";
import "@bryntum/schedulerpro/fontawesome/css/solid.css";
import "./timeline.css";

import { StringHelper } from "@bryntum/schedulerpro";
import { BryntumSchedulerPro, type BryntumSchedulerProProps } from "@bryntum/schedulerpro-react";
import { useMemo, useSyncExternalStore } from "react";
import type { TimelineEvent, TimelineLabels, TimelinePayload } from "./types";

export interface RepaymentTimelineProps {
  payload: TimelinePayload;
  /** The business date (`YYYY-MM-DD`): the "today" line follows the demo clock, not the wall clock. */
  today: string;
  labels: TimelineLabels;
  /** Height of the chart area in pixels. The component needs a real height to render. */
  height?: number;
  className?: string;
}

const PREVIOUS_ROW = "previous-plan";
const DAY_MS = 86_400_000;

/**
 * Bryntum Scheduler Pro, read-only: the member's plan as one row of instalments coloured by the backend, the
 * previous plan (after a hardship change) as dashed ghost events on a second row with arrows to where each payment
 * moved, grace windows as shaded ranges and a line for the business date. A list mirrors the chart for screen
 * readers, so nothing on the canvas is the only way to read the plan.
 */
export function RepaymentTimeline({ payload, today, labels, height = 196, className }: RepaymentTimelineProps) {
  const reducedMotion = useReducedMotion();
  const config = useMemo(() => buildConfig(payload, today, labels), [payload, today, labels]);

  return (
    <figure className={["seed-timeline", className].filter(Boolean).join(" ")} aria-label={labels.title}>
      <div className="seed-timeline__chart" style={{ height }} aria-hidden="true">
        <BryntumSchedulerPro {...config} useInitialAnimation={reducedMotion ? false : "fade-in"} />
      </div>
      <figcaption className="sr-only">
        <p>{labels.title}</p>
        <ScreenReaderList title={labels.currentPlan} events={payload.events} labels={labels} />
        {payload.ghostEvents.length > 0 ? (
          <ScreenReaderList title={labels.previousPlan} events={payload.ghostEvents} labels={labels} />
        ) : null}
      </figcaption>
    </figure>
  );
}

function ScreenReaderList({ title, events, labels }: { title: string; events: TimelineEvent[]; labels: TimelineLabels }) {
  return (
    <>
      <p>{title}</p>
      <ul>
        {events.map((event) => (
          <li key={event.id}>
            {labels.formatDate(event.startDate)}: {event.name}, {labels.status[event.status] ?? event.status}
          </li>
        ))}
      </ul>
    </>
  );
}

function buildConfig(payload: TimelinePayload, today: string, labels: TimelineLabels): BryntumSchedulerProProps {
  const current = payload.resources[0];
  const hasGhosts = payload.ghostEvents.length > 0;
  const resources = [
    { id: current?.id ?? "plan", name: labels.currentPlan },
    ...(hasGhosts ? [{ id: PREVIOUS_ROW, name: labels.previousPlan }] : []),
  ];
  const events = [
    ...payload.events.map((event) => toBryntumEvent(event, event.resourceId, "tonal")),
    ...payload.ghostEvents.map((event) => toBryntumEvent(event, PREVIOUS_ROW, "dashed")),
  ];
  const dates = [...payload.events, ...payload.ghostEvents].flatMap((event) => [event.startDate, event.endDate]);
  dates.push(today);
  const { start, end } = visibleRange(dates);

  return {
    readOnly: true,
    resources,
    events,
    dependencies: payload.dependencies.map((d, index) => ({ id: `dep-${index}`, from: d.from, to: d.to, cls: "seed-shift" })),
    timeRanges: [
      ...payload.timeRanges.map((range) => ({ ...range, cls: "seed-grace" })),
      { id: "today", startDate: today, name: labels.today, cls: "seed-today" },
    ],
    startDate: start,
    endDate: end,
    viewPreset: "weekAndMonth",
    tickSize: 56,
    rowHeight: 64,
    barMargin: 14,
    eventLayout: "none",
    columns: [{ type: "resourceInfo", text: "", field: "name", width: 168, showImage: false, sortable: false }],
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
    dependenciesFeature: { allowCreate: false },
    eventTooltipFeature: {
      template: ({ eventRecord }: { eventRecord: { name?: unknown; startDate?: unknown; getData?: (field: string) => unknown } }) => {
        const status = String(eventRecord.getData?.("status") ?? "");
        const iso = String(eventRecord.getData?.("dueDate") ?? "");
        return `<strong>${StringHelper.encodeHtml(String(eventRecord.name ?? ""))}</strong>`
          + `<br>${StringHelper.encodeHtml(iso ? labels.formatDate(iso) : "")}`
          + `<br>${StringHelper.encodeHtml(labels.status[status] ?? status)}`;
      },
    },
    zoomOnMouseWheel: false,
    zoomOnTimeAxisDoubleClick: false,
    enableEventAnimations: false,
    ariaLabel: labels.title,
  };
}

function toBryntumEvent(event: TimelineEvent, resourceId: string, eventStyle: "tonal" | "dashed") {
  return {
    id: event.id,
    resourceId,
    startDate: event.startDate,
    endDate: event.endDate,
    name: event.name,
    eventColor: event.eventColor,
    eventStyle,
    readOnly: true,
    draggable: false,
    resizable: false,
    cls: eventStyle === "dashed" ? "seed-ghost" : `seed-status-${event.status.toLowerCase()}`,
    // Kept for the tooltip and the screen-reader text.
    status: event.status,
    dueDate: event.startDate,
  };
}

/** Pads the plan with a little air on both sides so the first and last payments are not on the edge. */
function visibleRange(isoDates: string[]): { start: Date; end: Date } {
  const times = isoDates.map(toLocalTime).filter((t) => Number.isFinite(t));
  const first = times.length > 0 ? Math.min(...times) : Date.now();
  const last = times.length > 0 ? Math.max(...times) : Date.now();
  return { start: new Date(first - 21 * DAY_MS), end: new Date(last + 35 * DAY_MS) };
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
