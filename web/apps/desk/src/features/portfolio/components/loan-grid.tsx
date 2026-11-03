"use client";

import type { Schemas } from "@paypal-seed/api-client/types";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import {
  AllCommunityModule,
  type CellKeyDownEvent,
  type ColDef,
  type ICellRendererParams,
  type IDatasource,
  type IGetRowsParams,
  ModuleRegistry,
  type RowClickedEvent,
  themeQuartz,
  type ValueFormatterParams,
} from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useCallback, useMemo, useRef, useState } from "react";
import { AGING_BUCKETS, LOAN_STATUSES, SORT_COLUMNS, type SortColumn } from "../model";

ModuleRegistry.registerModules([AllCommunityModule]);

type LoanRow = Schemas["DeskLoanRow"];
type LoanPage = Schemas["LoanPage"];

/** Same Quartz re-colouring as the hardship queue, so the grids read as one desk. */
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

/** One grid block is one backend page, so a block's cursor is the previous block's `nextCursor`. */
const PAGE_SIZE = 100;
/** A scrollbar jump far past what is loaded must walk every page in between; refuse past this many. */
const MAX_WALK_PAGES = 25;

const isSortColumn = (value: string): value is SortColumn => (SORT_COLUMNS as readonly string[]).includes(value);

function StatusCell({ value }: ICellRendererParams<LoanRow, LoanRow["status"]>) {
  const status = value ? LOAN_STATUSES[value] : undefined;
  return status ? <StatusPill tone={status.tone}>{status.label}</StatusPill> : null;
}

function BucketCell({ value }: ICellRendererParams<LoanRow, LoanRow["agingBucket"]>) {
  const bucket = value ? AGING_BUCKETS[value] : undefined;
  return bucket ? (
    <StatusPill tone={bucket.tone} dot={false}>
      {bucket.label}
    </StatusPill>
  ) : null;
}

function AutopayCell({ value }: ICellRendererParams<LoanRow, boolean>) {
  return value ? (
    <StatusPill tone="info" dot={false}>
      On
    </StatusPill>
  ) : (
    <span className="text-muted-ink">Off</span>
  );
}

function HardshipCell({ value }: ICellRendererParams<LoanRow, boolean>) {
  return value ? (
    <StatusPill tone="warning" dot={false}>
      Open case
    </StatusPill>
  ) : (
    <span className="text-muted-ink">None</span>
  );
}

function NoRows() {
  return (
    <div role="status" className="flex flex-col items-center gap-1 text-center">
      <p className="font-display text-xl font-light text-ink">No loans match</p>
      <p className="text-sm text-muted-ink">Change the filters above.</p>
    </div>
  );
}

const money = ({ value }: ValueFormatterParams<LoanRow, number>) => (typeof value === "number" ? formatMoney(value) : "");
const businessDate = ({ value }: ValueFormatterParams<LoanRow, string>) => (value ? formatBusinessDate(value) : "—");

async function fetchPage(query: URLSearchParams, signal: AbortSignal): Promise<LoanPage> {
  const response = await fetch(`/api/portfolio/loans?${query.toString()}`, { signal, cache: "no-store" });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { title?: string };
    throw new Error(body.title ?? "The loans could not be loaded.");
  }
  return (await response.json()) as LoanPage;
}

export interface LoanGridProps {
  /** Filters from the URL as a query string for `/api/portfolio/loans`. */
  query: string;
}

/**
 * The loan portfolio. AG Grid's infinite row model asks for one block at a time; each block is one keyset page, so
 * sorting is done by the backend and a scrollbar jump walks the cursors forward. A new sort or filter starts over.
 */
export function LoanGrid({ query }: LoanGridProps) {
  const router = useRouter();
  const gridRef = useRef<AgGridReact<LoanRow>>(null);
  const [error, setError] = useState<string | null>(null);
  const [total, setTotal] = useState<{ value: number; exact: boolean } | null>(null);
  const [asOf, setAsOf] = useState<string | null>(null);

  const datasource = useMemo<IDatasource>(() => {
    // The cursor to send for the page that starts at each row index, learned as pages arrive. Reset on a new sort.
    const paging = { signature: "", cursors: new Map<number, string>() };
    const abort = new AbortController();

    return {
      async getRows(params: IGetRowsParams) {
        const sort = params.sortModel[0];
        const nextSignature = sort ? `${sort.colId}:${sort.sort}` : "";
        if (nextSignature !== paging.signature) {
          paging.signature = nextSignature;
          paging.cursors = new Map();
        }
        const base = new URLSearchParams(query);
        base.set("limit", String(PAGE_SIZE));
        if (sort && isSortColumn(sort.colId)) {
          base.set("sort", `${sort.colId}:${sort.sort}`);
        }
        const pageFor = async (start: number): Promise<LoanPage> => {
          const request = new URLSearchParams(base);
          const cursor = paging.cursors.get(start);
          if (cursor) {
            request.set("cursor", cursor);
          }
          const page = await fetchPage(request, abort.signal);
          if (page.nextCursor) {
            paging.cursors.set(start + PAGE_SIZE, page.nextCursor);
          }
          return page;
        };
        try {
          for (let walked = 0; params.startRow > 0 && !paging.cursors.has(params.startRow); walked++) {
            if (walked >= MAX_WALK_PAGES) {
              throw new Error("That is too far down to jump to. Sort or filter to narrow the list.");
            }
            const known = [...paging.cursors.keys()].filter((key) => key < params.startRow);
            const from = known.length ? Math.max(...known) : 0;
            const page = await pageFor(from);
            if (!page.nextCursor) {
              params.successCallback([], from + (page.rows ?? []).length);
              return;
            }
          }
          const page = await pageFor(params.startRow);
          if (params.startRow === 0) {
            setTotal(page.total ?? null);
          }
          setAsOf(page.asOf);
          setError(null);
          const rows = page.rows ?? [];
          params.successCallback(rows, page.nextCursor ? -1 : params.startRow + rows.length);
        } catch (reason) {
          if (!(reason instanceof DOMException && reason.name === "AbortError")) {
            setError(reason instanceof Error ? reason.message : "The loans could not be loaded.");
            params.failCallback();
          }
        }
      },
      destroy() {
        abort.abort();
      },
    };
  }, [query]);

  const columns = useMemo<ColDef<LoanRow>[]>(
    () => [
      { field: "status", headerName: "Status", cellRenderer: StatusCell, width: 170, pinned: "left", sortable: true },
      { field: "principalCents", headerName: "Principal", valueFormatter: money, type: "rightAligned", width: 130, sortable: true },
      { field: "outstandingCents", headerName: "Outstanding", valueFormatter: money, type: "rightAligned", width: 140, sortable: true },
      { field: "nextDueDate", headerName: "Next due", valueFormatter: businessDate, width: 140, sortable: true },
      { field: "daysLate", headerName: "Days late", type: "rightAligned", width: 120, sortable: true },
      { field: "agingBucket", headerName: "Aging", cellRenderer: BucketCell, width: 190, sortable: true },
      { field: "autopay", headerName: "Autopay", cellRenderer: AutopayCell, width: 120 },
      { field: "hardship", headerName: "Hardship", cellRenderer: HardshipCell, width: 130 },
      { field: "disbursedOn", headerName: "Disbursed", valueFormatter: businessDate, flex: 1, minWidth: 140, sort: "desc", sortable: true },
    ],
    [],
  );

  const defaultColDef = useMemo<ColDef<LoanRow>>(() => ({ resizable: true, sortable: false, suppressHeaderMenuButton: true }), []);

  const open = useCallback(
    (loanId: string | undefined) => {
      if (loanId) {
        router.push(`/loans/${loanId}` as Route);
      }
    },
    [router],
  );
  const onRowClicked = useCallback((event: RowClickedEvent<LoanRow>) => open(event.data?.loanId), [open]);
  const onCellKeyDown = useCallback(
    (event: CellKeyDownEvent<LoanRow>) => {
      if ((event.event as KeyboardEvent | null)?.key === "Enter") {
        open(event.data?.loanId);
      }
    },
    [open],
  );

  return (
    <section aria-label="Loans" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-ink tabular-nums" aria-live="polite">
          {total ? `${total.exact ? "" : "About "}${total.value.toLocaleString("en-US")} loan${total.value === 1 ? "" : "s"}` : "Loading…"}
          {asOf
            ? ` · as of ${new Date(asOf).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short", timeZone: "America/Los_Angeles" })}`
            : ""}
        </p>
        <Button variant="ghost" size="sm" onClick={() => gridRef.current?.api.refreshInfiniteCache()}>
          Refresh
        </Button>
      </div>
      {error ? (
        <p role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="h-[560px] w-full">
        <AgGridReact<LoanRow>
          ref={gridRef}
          theme={seedTheme}
          rowModelType="infinite"
          datasource={datasource}
          cacheBlockSize={PAGE_SIZE}
          maxConcurrentDatasourceRequests={1}
          infiniteInitialRowCount={PAGE_SIZE}
          columnDefs={columns}
          defaultColDef={defaultColDef}
          getRowId={(params) => params.data.loanId}
          suppressMultiSort
          onRowClicked={onRowClicked}
          onCellKeyDown={onCellKeyDown}
          noRowsOverlayComponent={NoRows}
          rowClass="cursor-pointer"
          ensureDomOrder
          enableCellTextSelection
          rowSelection={{ mode: "singleRow", checkboxes: false, enableClickSelection: true }}
        />
      </div>
      <p className="text-xs text-muted-ink">
        Enter or click opens a loan. Sorting runs on the server over every loan, not only the ones loaded.
      </p>
    </section>
  );
}
