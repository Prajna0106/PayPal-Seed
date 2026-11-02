"use client";

import type { Schemas } from "@paypal-seed/api-client/types";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime, formatRelative } from "@paypal-seed/ui/lib/format";
import {
  AllCommunityModule,
  type CellKeyDownEvent,
  type ColDef,
  type GridApi,
  type GridReadyEvent,
  type IDatasource,
  type ICellRendererParams,
  ModuleRegistry,
  type RowClickedEvent,
  themeQuartz,
  type ValueFormatterParams,
} from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { type FlagStatus, FLAG_STATUSES, signalName } from "../model";

ModuleRegistry.registerModules([AllCommunityModule]);

type FlagRow = Schemas["FlagView"];
interface GridContext {
  threshold: number | null;
}

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
  rowHeight: 56,
  headerHeight: 44,
  cellHorizontalPadding: 16,
});

const BLOCK_SIZE = 50;

function StatusCell({ value }: ICellRendererParams<FlagRow, FlagStatus>) {
  const status = value ? FLAG_STATUSES[value] : undefined;
  return status ? <StatusPill tone={status.tone}>{status.label}</StatusPill> : null;
}

function WeightCell({ value, context }: ICellRendererParams<FlagRow, number, GridContext>) {
  if (typeof value !== "number") {
    return null;
  }
  const threshold = context.threshold;
  return (
    <span className="inline-flex items-baseline gap-1 tabular-nums">
      <span className="font-medium text-ink">{value}</span>
      {threshold === null ? null : <span className="text-xs text-muted-ink">of {threshold} to flag</span>}
    </span>
  );
}

function SignalsCell({ value }: ICellRendererParams<FlagRow, string[]>) {
  const codes = value ?? [];
  if (codes.length === 0) {
    return <span className="text-muted-ink">—</span>;
  }
  return (
    <span className="flex min-w-0 items-center gap-1.5">
      {codes.slice(0, 2).map((code) => (
        <StatusPill key={code} tone="neutral" dot={false} className="max-w-48 truncate">
          {signalName(code)}
        </StatusPill>
      ))}
      {codes.length > 2 ? <span className="text-xs text-muted-ink">+{codes.length - 2}</span> : null}
    </span>
  );
}

function NoRows() {
  return (
    <div role="status" className="flex flex-col items-center gap-1 text-center">
      <p className="font-display text-xl font-light text-ink">No flags here</p>
      <p className="text-sm text-muted-ink">Nothing matches this filter. Flags appear when a member&apos;s activity adds up to the threshold.</p>
    </div>
  );
}

interface FlagsPayload {
  rows: FlagRow[];
  nextBefore: string | null;
  threshold: number | null;
}

export interface FlagGridProps {
  /** Query string for `/api/fraud/flags`, built from the URL filter. */
  query: string;
}

/**
 * The fraud flag queue. AG Grid's infinite row model asks for blocks of rows as the officer scrolls; each block is one
 * page of the backend's keyset API (newest first), so the next block starts from the previous block's cursor.
 */
export function FlagGrid({ query }: FlagGridProps) {
  const router = useRouter();
  const apiRef = useRef<GridApi<FlagRow> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [threshold, setThreshold] = useState<number | null>(null);
  const [count, setCount] = useState<number | null>(null);

  const [reloads, setReloads] = useState(0);

  const fetchPage = useCallback(
    async (before: string | undefined, signal?: AbortSignal): Promise<FlagsPayload> => {
      const params = new URLSearchParams(query);
      params.set("limit", String(BLOCK_SIZE));
      if (before) {
        params.set("before", before);
      }
      const response = await fetch(`/api/fraud/flags?${params.toString()}`, { cache: "no-store", ...(signal ? { signal } : {}) });
      if (!response.ok) {
        const body = (await response.json().catch(() => ({}))) as { title?: string };
        throw new Error(body.title ?? "The flags could not be loaded.");
      }
      return (await response.json()) as FlagsPayload;
    },
    [query],
  );

  // Each datasource keeps the cursor that starts every block it has seen; block 0 has none.
  const datasource = useMemo<IDatasource>(() => {
    void reloads;
    const known = new Map<number, string | undefined>([[0, undefined]]);
    return {
      getRows: (params) => {
        const run = async () => {
          // A block can only be fetched after the one before it gave its cursor, so catch up in order.
          let start = Math.max(...[...known.keys()].filter((key) => key <= params.startRow));
          let page: FlagsPayload;
          for (;;) {
            page = await fetchPage(known.get(start));
            if (start === 0) {
              setThreshold(page.threshold);
            }
            if (page.nextBefore !== null) {
              known.set(start + BLOCK_SIZE, page.nextBefore);
            }
            if (start >= params.startRow || page.nextBefore === null) {
              break;
            }
            start += BLOCK_SIZE;
          }
          if (start < params.startRow) {
            params.successCallback([], start + page.rows.length);
            setCount(start + page.rows.length);
            return;
          }
          const last = page.nextBefore === null ? start + page.rows.length : undefined;
          params.successCallback(page.rows, last);
          setCount(last ?? null);
          setError(null);
        };
        run().catch((reason: unknown) => {
          setError(reason instanceof Error ? reason.message : "The flags could not be loaded.");
          params.failCallback();
        });
      },
    };
  }, [fetchPage, reloads]);

  useEffect(() => {
    apiRef.current?.refreshCells({ force: true });
  }, [threshold]);

  const columns = useMemo<ColDef<FlagRow>[]>(
    () => [
      { field: "status", headerName: "Status", cellRenderer: StatusCell, width: 140, pinned: "left" },
      {
        field: "memberId",
        headerName: "Member",
        width: 130,
        cellClass: "font-mono text-xs",
        valueFormatter: ({ value }: ValueFormatterParams<FlagRow, string>) => (value ? value.slice(0, 8) : ""),
      },
      { field: "score", headerName: "Total weight", cellRenderer: WeightCell, width: 190 },
      { field: "signalCodes", headerName: "Top signals", cellRenderer: SignalsCell, flex: 1, minWidth: 260 },
      {
        field: "raisedAt",
        headerName: "Age",
        width: 150,
        valueFormatter: ({ value }: ValueFormatterParams<FlagRow, string>) => (value ? formatRelative(value) : ""),
        tooltipValueGetter: ({ value }) => (typeof value === "string" ? formatDateTime(value) : undefined),
      },
    ],
    [],
  );

  const defaultColDef = useMemo<ColDef<FlagRow>>(() => ({ sortable: false, resizable: true, suppressMovable: true }), []);

  const open = useCallback(
    (flagId: string | undefined) => {
      if (flagId) {
        router.push(`/fraud/${flagId}` as Route);
      }
    },
    [router],
  );
  const onRowClicked = useCallback((event: RowClickedEvent<FlagRow>) => open(event.data?.flagId), [open]);
  const onCellKeyDown = useCallback(
    (event: CellKeyDownEvent<FlagRow>) => {
      if ((event.event as KeyboardEvent | null)?.key === "Enter") {
        open(event.data?.flagId);
      }
    },
    [open],
  );
  const onGridReady = useCallback((event: GridReadyEvent<FlagRow>) => {
    apiRef.current = event.api;
  }, []);

  const context = useMemo<GridContext>(() => ({ threshold }), [threshold]);

  return (
    <section aria-label="Fraud flags" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-ink tabular-nums" aria-live="polite">
          {count === null ? "Scroll to load more flags" : `${count.toLocaleString("en-US")} flag${count === 1 ? "" : "s"}`}
          {threshold === null ? "" : ` · a flag opens at a total weight of ${threshold}`}
        </p>
        <Button variant="ghost" size="sm" className="min-h-11 sm:min-h-8" onClick={() => setReloads((n) => n + 1)}>
          Refresh
        </Button>
      </div>
      {error ? (
        <p role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="h-[calc(100dvh-22rem)] min-h-[420px] w-full">
        <AgGridReact<FlagRow>
          theme={seedTheme}
          rowModelType="infinite"
          datasource={datasource}
          cacheBlockSize={BLOCK_SIZE}
          maxBlocksInCache={20}
          context={context}
          columnDefs={columns}
          defaultColDef={defaultColDef}
          getRowId={(params) => params.data.flagId}
          onGridReady={onGridReady}
          onRowClicked={onRowClicked}
          onCellKeyDown={onCellKeyDown}
          noRowsOverlayComponent={NoRows}
          rowClass="cursor-pointer"
          ensureDomOrder
          enableCellTextSelection
          tooltipShowDelay={300}
          rowSelection={{ mode: "singleRow", checkboxes: false, enableClickSelection: true }}
        />
      </div>
      <p className="text-xs text-muted-ink">Enter or click opens a flag. Flags are advice for officers; they never block a member.</p>
    </section>
  );
}
