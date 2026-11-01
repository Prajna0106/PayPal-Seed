"use client";

import type { HardshipCaseStatus, HardshipQueueRow } from "@paypal-seed/api-client/types";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import {
  AllCommunityModule,
  type CellKeyDownEvent,
  type ColDef,
  type GridState,
  type ICellRendererParams,
  ModuleRegistry,
  type RowClickedEvent,
  type StateUpdatedEvent,
  themeQuartz,
  type ValueFormatterParams,
} from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import { Download, RotateCcw, Search } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { CASE_STATUSES, OPTION_NAMES, ORIGINS, type RiskLevel, RISK_LEVELS } from "../model";

ModuleRegistry.registerModules([AllCommunityModule]);

/** AG Grid's Quartz theme, re-coloured with Seed's tokens so the grid reads as part of the desk, not a widget. */
const seedTheme = themeQuartz.withParams({
  fontFamily: "var(--font-inter), ui-sans-serif, system-ui, sans-serif",
  headerFontFamily: "var(--font-inter), ui-sans-serif, system-ui, sans-serif",
  fontSize: 14,
  headerFontSize: 12,
  headerFontWeight: 500,
  foregroundColor: "var(--color-ink)",
  textColor: "var(--color-ink)",
  backgroundColor: "var(--color-surface)",
  headerBackgroundColor: "var(--color-canvas-soft)",
  headerTextColor: "var(--color-muted-ink)",
  borderColor: "var(--color-hairline)",
  rowBorder: { color: "var(--color-hairline-soft)" },
  columnBorder: false,
  wrapperBorder: { color: "var(--color-hairline)" },
  wrapperBorderRadius: 16,
  borderRadius: 8,
  accentColor: "var(--color-ink)",
  rowHoverColor: "var(--color-canvas-soft)",
  selectedRowBackgroundColor: "var(--color-surface-strong)",
  oddRowBackgroundColor: "var(--color-surface)",
  spacing: 7,
  rowHeight: 52,
  headerHeight: 44,
  cellHorizontalPadding: 16,
});

const STATE_KEY = "seed-desk:hardship-queue:grid-state:v1";
const PAGE_SIZE = 100;
/** The desk shows the latest cases; older ones are one filter away. Keeps the client-side features instant. */
const MAX_ROWS = 2000;

function StatusCell({ value }: ICellRendererParams<HardshipQueueRow, HardshipCaseStatus>) {
  const status = value ? CASE_STATUSES[value] : undefined;
  return status ? <StatusPill tone={status.tone}>{status.label}</StatusPill> : null;
}

function LevelCell({ value, data }: ICellRendererParams<HardshipQueueRow, RiskLevel>) {
  const level = value ? RISK_LEVELS[value] : undefined;
  if (!level) {
    return null;
  }
  return (
    <span className="inline-flex items-center gap-2">
      <StatusPill tone={level.tone} dot={false}>
        {level.label}
      </StatusPill>
      <span className="text-xs text-muted-ink tabular-nums">{data?.points ?? 0} pts</span>
    </span>
  );
}

function ChosenCell({ value }: ICellRendererParams<HardshipQueueRow, string | null>) {
  if (!value) {
    return <span className="text-muted-ink">—</span>;
  }
  return (
    <span className="inline-flex items-center gap-2">
      <span className="inline-flex size-6 items-center justify-center rounded-full bg-ink text-xs font-medium text-white">
        {value}
      </span>
      <span className="truncate">{OPTION_NAMES[value] ?? value}</span>
    </span>
  );
}

function CaseCell({ value }: ICellRendererParams<HardshipQueueRow, string>) {
  if (!value) {
    return null;
  }
  return (
    <Link
      href={`/hardship/${value}` as Route}
      className="font-mono text-xs text-ink underline-offset-4 hover:underline"
      tabIndex={-1}
      prefetch={false}
    >
      {value.slice(0, 8)}
    </Link>
  );
}

function NoRows() {
  return (
    <div role="status" className="flex flex-col items-center gap-1 text-center">
      <p className="font-display text-xl font-light text-ink">No cases match</p>
      <p className="text-sm text-muted-ink">Change the filters, or run the daily scan from Operations.</p>
    </div>
  );
}

const money = ({ value }: ValueFormatterParams<HardshipQueueRow, number>) =>
  typeof value === "number" ? formatMoney(value) : "";
const businessDate = ({ value }: ValueFormatterParams<HardshipQueueRow, string>) =>
  value ? formatBusinessDate(value) : "";

function readState(): GridState | undefined {
  try {
    const raw = window.localStorage.getItem(STATE_KEY);
    return raw ? (JSON.parse(raw) as GridState) : undefined;
  } catch {
    return undefined;
  }
}

export interface QueueGridProps {
  /** Query string for `/api/hardship/cases`, built from the URL filters. */
  query: string;
}

/**
 * The hardship queue. Pages stream in over the backend's keyset API (newest first) and land in AG Grid's
 * client-side model, so sorting, column filters, the quick filter and CSV export work across every loaded row.
 * Column layout, sort and filters persist per browser.
 */
export function QueueGrid({ query }: QueueGridProps) {
  const router = useRouter();
  const gridRef = useRef<AgGridReact<HardshipQueueRow>>(null);
  const [rows, setRows] = useState<HardshipQueueRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [quickFilter, setQuickFilter] = useState("");
  const [initialState] = useState(readState);
  const [reloads, setReloads] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    async function load() {
      setRows(null);
      setError(null);
      setTruncated(false);
      let before: string | null = null;
      let loaded: HardshipQueueRow[] = [];
      do {
        const params = new URLSearchParams(query);
        params.set("limit", String(PAGE_SIZE));
        if (before) {
          params.set("before", before);
        }
        const response = await fetch(`/api/hardship/cases?${params.toString()}`, {
          signal: controller.signal,
          cache: "no-store",
        });
        if (!response.ok) {
          const body = (await response.json().catch(() => ({}))) as { title?: string };
          throw new Error(body.title ?? "The queue could not be loaded.");
        }
        const page = (await response.json()) as { rows: HardshipQueueRow[]; nextBefore: string | null };
        loaded = loaded.concat(page.rows);
        before = page.nextBefore;
        if (!cancelled) {
          setRows(loaded);
        }
      } while (before && loaded.length < MAX_ROWS && !cancelled);
      if (!cancelled) {
        setTruncated(Boolean(before));
      }
    }
    load().catch((reason: unknown) => {
      if (!cancelled && !(reason instanceof DOMException && reason.name === "AbortError")) {
        setError(reason instanceof Error ? reason.message : "The queue could not be loaded.");
        setRows([]);
      }
    });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [query, reloads]);

  const columns = useMemo<ColDef<HardshipQueueRow>[]>(
    () => [
      { field: "status", headerName: "Status", cellRenderer: StatusCell, width: 170, filter: "agTextColumnFilter", pinned: "left" },
      { field: "level", headerName: "Level", cellRenderer: LevelCell, width: 200, filter: "agTextColumnFilter" },
      {
        field: "targetAmountCents",
        headerName: "Payment",
        valueFormatter: money,
        type: "rightAligned",
        width: 120,
        filter: "agNumberColumnFilter",
      },
      { field: "targetDueDate", headerName: "Due", valueFormatter: businessDate, width: 140, filter: "agDateColumnFilter" },
      { field: "targetSeq", headerName: "No.", width: 80, type: "rightAligned" },
      { field: "chosenOption", headerName: "Member chose", cellRenderer: ChosenCell, flex: 1, minWidth: 190 },
      {
        field: "origin",
        headerName: "Origin",
        valueFormatter: ({ value }: ValueFormatterParams<HardshipQueueRow, string>) =>
          value ? (ORIGINS[value as keyof typeof ORIGINS] ?? value) : "",
        width: 140,
        filter: "agTextColumnFilter",
      },
      { field: "openedOn", headerName: "Opened", valueFormatter: businessDate, width: 140, sort: "desc", filter: "agDateColumnFilter" },
      { field: "caseId", headerName: "Case", cellRenderer: CaseCell, width: 110, sortable: false },
    ],
    [],
  );

  const defaultColDef = useMemo<ColDef<HardshipQueueRow>>(
    () => ({ sortable: true, resizable: true, suppressHeaderMenuButton: false, floatingFilter: false }),
    [],
  );

  const open = useCallback((caseId: string | undefined) => {
    if (caseId) {
      router.push(`/hardship/${caseId}` as Route);
    }
  }, [router]);

  const onRowClicked = useCallback((event: RowClickedEvent<HardshipQueueRow>) => open(event.data?.caseId), [open]);
  const onCellKeyDown = useCallback(
    (event: CellKeyDownEvent<HardshipQueueRow>) => {
      const key = (event.event as KeyboardEvent | null)?.key;
      if (key === "Enter") {
        open(event.data?.caseId);
      }
    },
    [open],
  );

  const onStateUpdated = useCallback((event: StateUpdatedEvent<HardshipQueueRow>) => {
    const { columnOrder, columnPinning, columnSizing, columnVisibility, sort, filter } = event.state;
    try {
      window.localStorage.setItem(
        STATE_KEY,
        JSON.stringify({ columnOrder, columnPinning, columnSizing, columnVisibility, sort, filter }),
      );
    } catch {
      // Storage may be full or blocked; the layout simply is not remembered.
    }
  }, []);

  const resetLayout = () => {
    try {
      window.localStorage.removeItem(STATE_KEY);
    } catch {
      // Nothing to clear.
    }
    gridRef.current?.api.resetColumnState();
    gridRef.current?.api.setFilterModel(null);
  };

  const count = rows?.length ?? 0;

  return (
    <section aria-label="Hardship cases" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="relative w-full max-w-xs">
          <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-ink" />
          <Input
            type="search"
            value={quickFilter}
            onChange={(event) => setQuickFilter(event.target.value)}
            placeholder="Filter loaded cases…"
            aria-label="Filter loaded cases"
            className="pl-9"
          />
        </div>
        <div className="flex items-center gap-2">
          <p className="text-sm text-muted-ink tabular-nums" aria-live="polite">
            {rows === null ? "Loading…" : `${count.toLocaleString("en-US")} case${count === 1 ? "" : "s"}${truncated ? " (latest)" : ""}`}
          </p>
          <Button variant="ghost" size="sm" onClick={resetLayout}>
            <RotateCcw aria-hidden="true" />
            Reset layout
          </Button>
          <Button variant="ghost" size="sm" onClick={() => setReloads((n) => n + 1)}>
            Refresh
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!count}
            onClick={() => gridRef.current?.api.exportDataAsCsv({ fileName: "hardship-queue.csv" })}
          >
            <Download aria-hidden="true" />
            CSV
          </Button>
        </div>
      </div>
      {error ? (
        <p role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="h-[calc(100dvh-19rem)] min-h-[440px] w-full">
        <AgGridReact<HardshipQueueRow>
          ref={gridRef}
          theme={seedTheme}
          rowData={rows ?? []}
          loading={rows === null}
          columnDefs={columns}
          defaultColDef={defaultColDef}
          getRowId={(params) => params.data.caseId ?? ""}
          quickFilterText={quickFilter}
          initialState={initialState}
          onStateUpdated={onStateUpdated}
          onRowClicked={onRowClicked}
          onCellKeyDown={onCellKeyDown}
          noRowsOverlayComponent={NoRows}
          rowClass="cursor-pointer"
          animateRows
          ensureDomOrder
          enableCellTextSelection
          suppressCellFocus={false}
          rowSelection={{ mode: "singleRow", checkboxes: false, enableClickSelection: true }}
        />
      </div>
      <p className="text-xs text-muted-ink">
        Enter or click opens a case. Columns can be moved, resized, sorted and filtered; the layout is remembered in this browser.
      </p>
    </section>
  );
}
