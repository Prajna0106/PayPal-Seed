"use client";

import "@bryntum/schedulerpro/schedulerpro.css";
import "@bryntum/schedulerpro/svalbard-light.css";
import "@bryntum/schedulerpro/fontawesome/css/fontawesome.css";
import "@bryntum/schedulerpro/fontawesome/css/solid.css";
import "./workload-scheduler.css";

import { StringHelper } from "@bryntum/schedulerpro";
import { BryntumSchedulerPro, type BryntumSchedulerProProps } from "@bryntum/schedulerpro-react";
import { useMemo, useSyncExternalStore } from "react";

/** How close the oldest waiting item is to its deadline. The backend decides this; the chart only colours it. */
export type WorkloadSla = "OVERDUE" | "DUE_SOON" | "ON_TRACK" | "NO_SLA";

export interface WorkloadRow {
  id: string;
  name: string;
}

export interface WorkloadBar {
  id: string;
  rowId: string;
  sla: WorkloadSla;
  /** Instants (ISO 8601 with a zone). */
  start: string;
  end: string;
  /** Text on the bar, for example "4 waiting". */
  label: string;
  /** Tooltip second line, for example "Oldest deadline Nov 3, 9:00 AM". */
  detail: string;
}

export interface WorkloadLabels {
  /** Accessible name of the whole chart. */
  title: string;
  /** Name of the line drawn at the as-of time. */
  now: string;
  /** SLA state to plain words, used by the legend and the tooltip. */
  sla: Record<WorkloadSla, string>;
}

export interface WorkloadSchedulerProps {
  rows: WorkloadRow[];
  bars: WorkloadBar[];
  /** The backend's as-of instant: the line follows the server clock, not the browser's. */
  now: string;
  labels: WorkloadLabels;
  height?: number;
  className?: string;
}

const HOUR_MS = 3_600_000;
const LEGEND_ORDER: WorkloadSla[] = ["OVERDUE", "DUE_SOON", "ON_TRACK", "NO_SLA"];

/**
 * Bryntum Scheduler Pro, read-only: one row per officer role and kind of work, one bar per SLA bucket sized from now
 * to the oldest deadline in it (overdue bars run back from now). Colours are Seed tokens keyed by SLA state, and
 * state is also written in the legend and the tooltip, so colour is never the only signal. The chart is hidden from
 * assistive technology; the page pairs it with a table of the same figures.
 */
export function WorkloadScheduler({ rows, bars, now, labels, height = 280, className }: WorkloadSchedulerProps) {
  const reducedMotion = useReducedMotion();
  const config = useMemo(() => buildConfig(rows, bars, now, labels), [rows, bars, now, labels]);

  return (
    <figure className={["seed-workload", className].filter(Boolean).join(" ")} aria-label={labels.title}>
      <ul className="seed-workload__legend" aria-label={labels.title}>
        {LEGEND_ORDER.map((sla) => (
          <li key={sla}>
            <span className={`seed-workload__swatch seed-sla-${sla.toLowerCase()}`} aria-hidden="true" />
            {labels.sla[sla]}
          </li>
        ))}
        <li>
          <span className="seed-workload__swatch seed-workload__swatch--now" aria-hidden="true" />
          {labels.now}
        </li>
      </ul>
      <div className="seed-workload__chart" style={{ height }} aria-hidden="true">
        <BryntumSchedulerPro {...config} useInitialAnimation={reducedMotion ? false : "fade-in"} />
      </div>
    </figure>
  );
}

function buildConfig(rows: WorkloadRow[], bars: WorkloadBar[], now: string, labels: WorkloadLabels): BryntumSchedulerProProps {
  const nowTime = new Date(now).getTime();
  const times = bars.flatMap((bar) => [new Date(bar.start).getTime(), new Date(bar.end).getTime()]).filter(Number.isFinite);
  const first = Math.min(nowTime - 12 * HOUR_MS, ...times);
  const last = Math.max(nowTime + 36 * HOUR_MS, ...times);

  return {
    readOnly: true,
    resources: rows.map((row) => ({ id: row.id, name: row.name })),
    events: bars.map((bar) => ({
      id: bar.id,
      resourceId: bar.rowId,
      startDate: new Date(bar.start),
      endDate: new Date(bar.end),
      name: bar.label,
      eventStyle: "plain",
      readOnly: true,
      draggable: false,
      resizable: false,
      cls: `seed-sla-${bar.sla.toLowerCase()}`,
      sla: bar.sla,
      detail: bar.detail,
    })),
    timeRanges: [{ id: "now", startDate: new Date(nowTime), name: labels.now, cls: "seed-now" }],
    startDate: new Date(first - 2 * HOUR_MS),
    endDate: new Date(last + 2 * HOUR_MS),
    viewPreset: "hourAndDay",
    tickSize: 44,
    rowHeight: 56,
    barMargin: 8,
    eventLayout: "stack",
    columns: [{ type: "resourceInfo", text: "", field: "name", width: 200, showImage: false, sortable: false }],
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
        const sla = String(eventRecord.getData?.("sla") ?? "") as WorkloadSla;
        return `<strong>${StringHelper.encodeHtml(String(eventRecord.name ?? ""))}</strong>`
          + `<br>${StringHelper.encodeHtml(labels.sla[sla] ?? sla)}`
          + `<br>${StringHelper.encodeHtml(String(eventRecord.getData?.("detail") ?? ""))}`;
      },
    },
    zoomOnMouseWheel: false,
    zoomOnTimeAxisDoubleClick: false,
    enableEventAnimations: false,
    ariaLabel: labels.title,
  };
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
