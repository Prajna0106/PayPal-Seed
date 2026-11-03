"use client";

import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@paypal-seed/ui/components/sheet";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import {
  AllCommunityModule,
  type CellKeyDownEvent,
  type ColDef,
  type ICellRendererParams,
  ModuleRegistry,
  type RowClickedEvent,
} from "ag-grid-community";
import { AgGridReact } from "ag-grid-react";
import { ArrowUpRight, RotateCw } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FilterChip } from "@/features/applications/components/queue-toolbar";
import { seedGridTheme } from "@/features/applications/lib/grid-theme";
import { createKeysetDatasource, type KeysetPage } from "@/features/applications/lib/keyset-datasource";
import { shortRef } from "@/features/applications/model";
import {
  ASSESSMENT_STATUS_ORDER,
  ASSESSMENT_STATUSES,
  type DeskAssessmentRow,
  type FairCheckCard,
  type FairCheckFilters,
  NO_FAIR_CHECK_FILTERS,
} from "../model";
import { FairCheckPanel } from "./fair-check-panel";

ModuleRegistry.registerModules([AllCommunityModule]);

/** AG Grid's block size and the backend page size are the same number: one block is one keyset page. */
const BLOCK_SIZE = 50;

type Params = ICellRendererParams<DeskAssessmentRow>;

const Muted = () => <span className="text-muted-ink">—</span>;

function Pending() {
  return <span aria-hidden="true" className="inline-block h-3 w-20 animate-pulse rounded-full bg-surface-strong" />;
}

function MemberCell({ data }: Params) {
  return data ? <span className="font-mono text-xs">{shortRef(data.memberRef)}</span> : <Pending />;
}

function AmountCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  return (
    <span className="inline-flex items-center gap-2">
      <span className="tabular-nums">{formatMoney(data.recommendedAmountCents)}</span>
      {data.recommended ? null : (
        <StatusPill tone="warning" dot={false}>
          Not recommended
        </StatusPill>
      )}
    </span>
  );
}

function StatusCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  const status = ASSESSMENT_STATUSES[data.status];
  return <StatusPill tone={status.tone}>{status.label}</StatusPill>;
}

function DisputeCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  return data.disputed ? (
    <StatusPill tone="warning" dot={false}>
      Disputed
    </StatusPill>
  ) : (
    <Muted />
  );
}

function NoRows() {
  return (
    <div role="status" className="flex flex-col items-center gap-1 text-center">
      <p className="font-display text-xl font-light text-ink">No fair checks match</p>
      <p className="text-sm text-muted-ink">Change the filters, or check again after members apply.</p>
    </div>
  );
}

type Loaded = { id: string; token: number; card?: FairCheckCard; idempotencyKey?: string; error?: string };

/** One fair check for the side sheet, read through this app's route so the backend identity stays on the server. */
function SheetBody({ assessmentId, token, onDecided }: { assessmentId: string; token: number; onDecided: () => void }) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      try {
        const response = await fetch(`/api/fair-checks/${assessmentId}`, { cache: "no-store", signal: controller.signal });
        const body = (await response.json().catch(() => ({}))) as {
          title?: string;
          card?: FairCheckCard;
          idempotencyKey?: string;
        };
        if (!response.ok || !body.card || !body.idempotencyKey) {
          setLoaded({ id: assessmentId, token, error: body.title ?? "The fair check could not be loaded." });
          return;
        }
        setLoaded({ id: assessmentId, token, card: body.card, idempotencyKey: body.idempotencyKey });
      } catch (reason) {
        if (!(reason instanceof DOMException && reason.name === "AbortError")) {
          setLoaded({ id: assessmentId, token, error: "The fair check could not be loaded." });
        }
      }
    }
    void load();
    return () => controller.abort();
  }, [assessmentId, token]);

  if (!loaded || loaded.id !== assessmentId || loaded.token !== token) {
    return (
      <div className="flex flex-col gap-4 px-4 pb-6" aria-busy="true" aria-label="Loading the fair check">
        <Skeleton className="h-6 w-2/3" />
        <Skeleton className="h-24 rounded-xl" />
        <Skeleton className="h-48 rounded-xl" />
      </div>
    );
  }
  if (loaded.error || !loaded.card || !loaded.idempotencyKey) {
    return (
      <p role="alert" className="mx-4 rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
        {loaded.error ?? "The fair check could not be loaded."}
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-4 overflow-y-auto px-4 pb-8">
      <Button asChild variant="ghost" size="sm" className="self-start">
        <Link href={`/fair-checks/${assessmentId}` as Route}>
          Open as a page
          <ArrowUpRight aria-hidden="true" />
        </Link>
      </Button>
      <FairCheckPanel card={loaded.card} idempotencyKey={loaded.idempotencyKey} onDecided={onDecided} />
    </div>
  );
}

export interface FairChecksQueueProps {
  initialFilters: FairCheckFilters;
}

/**
 * The fair-check decisions queue: AG Grid's infinite row model over the backend's keyset list, with the evidence and
 * the decision form in a side sheet so an officer can work down the list without leaving it.
 */
export function FairChecksQueue({ initialFilters }: FairChecksQueueProps) {
  const gridRef = useRef<AgGridReact<DeskAssessmentRow>>(null);
  const [filters, setFilters] = useState(initialFilters);
  const appliedFilters = useRef(initialFilters);
  const [summary, setSummary] = useState("Loading…");
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [token, setToken] = useState(0);

  const [datasource] = useState(() =>
    createKeysetDatasource<DeskAssessmentRow, FairCheckFilters>({
      endpoint: "/api/fair-checks",
      initialFilters,
      onFirstPage: (page) => {
        const { total } = page as KeysetPage<DeskAssessmentRow>;
        if (!total) {
          return;
        }
        const count = total.value.toLocaleString("en-US");
        setSummary(`${total.exact ? "" : "About "}${count} fair check${total.value === 1 ? "" : "s"}`);
      },
      onError: setError,
    }),
  );

  useEffect(() => {
    if (appliedFilters.current === filters) {
      return;
    }
    appliedFilters.current = filters;
    datasource.setFilters(filters);
    setSummary("Loading…");
    datasource.reset();
    gridRef.current?.api?.purgeInfiniteCache();
    const params = new URLSearchParams();
    if (filters.status.length > 0) {
      params.set("status", filters.status.join(","));
    }
    if (filters.disputed) {
      params.set("disputed", "true");
    }
    const query = params.toString();
    window.history.replaceState(null, "", query ? `?${query}` : window.location.pathname);
  }, [filters, datasource]);

  const refresh = useCallback(() => {
    datasource.reset();
    setError(null);
    gridRef.current?.api?.purgeInfiniteCache();
  }, [datasource]);

  /** A decision changed the row's status: reload the sheet and re-read the blocks, keeping the rows on screen. */
  const onDecided = useCallback(() => {
    datasource.reset();
    gridRef.current?.api?.refreshInfiniteCache();
    setToken((n) => n + 1);
  }, [datasource]);

  const columns = useMemo<ColDef<DeskAssessmentRow>[]>(
    () => [
      { colId: "member", headerName: "Member", cellRenderer: MemberCell, width: 120, sortable: false, pinned: "left" },
      {
        colId: "recommendedAmountCents",
        field: "recommendedAmountCents",
        headerName: "Recommended amount",
        cellRenderer: AmountCell,
        width: 260,
      },
      { colId: "status", field: "status", headerName: "Status", cellRenderer: StatusCell, width: 190 },
      { colId: "disputed", field: "disputed", headerName: "Dispute", cellRenderer: DisputeCell, width: 130, sortable: false },
      {
        colId: "createdAt",
        field: "createdAt",
        headerName: "Received",
        valueFormatter: ({ value }) => (typeof value === "string" ? formatDateTime(value, { dateStyle: "short" }) : ""),
        width: 140,
        sortable: false,
      },
      {
        colId: "age",
        field: "ageDays",
        headerName: "Age",
        valueFormatter: ({ value }) => (typeof value === "number" ? `${value} d` : ""),
        type: "rightAligned",
        width: 100,
        sort: "asc",
      },
    ],
    [],
  );

  const defaultColDef = useMemo<ColDef<DeskAssessmentRow>>(() => ({ sortable: true, resizable: true }), []);

  const onRowClicked = useCallback((event: RowClickedEvent<DeskAssessmentRow>) => {
    if (event.data) {
      setSelected(event.data.assessmentId);
    }
  }, []);
  const onCellKeyDown = useCallback((event: CellKeyDownEvent<DeskAssessmentRow>) => {
    if ((event.event as KeyboardEvent | null)?.key === "Enter" && event.data) {
      setSelected(event.data.assessmentId);
    }
  }, []);

  const filtered = filters.status.length > 0 || filters.disputed;

  return (
    <section aria-label="Fair-check decisions" className="flex flex-col gap-3">
      <div className="flex flex-col gap-3">
        <div role="group" aria-label="Filter fair checks" className="flex flex-wrap items-center gap-2">
          {ASSESSMENT_STATUS_ORDER.map((status) => (
            <FilterChip
              key={status}
              pressed={filters.status.includes(status)}
              onClick={() =>
                setFilters((current) => ({
                  ...current,
                  status: current.status.includes(status)
                    ? current.status.filter((s) => s !== status)
                    : [...current.status, status],
                }))
              }
            >
              {ASSESSMENT_STATUSES[status].label}
            </FilterChip>
          ))}
          <span aria-hidden="true" className="mx-1 hidden h-6 w-px bg-hairline sm:block" />
          <FilterChip pressed={filters.disputed} onClick={() => setFilters((current) => ({ ...current, disputed: !current.disputed }))}>
            Disputed only
          </FilterChip>
          {filtered ? (
            <Button variant="ghost" size="sm" onClick={() => setFilters(NO_FAIR_CHECK_FILTERS)}>
              Clear filters
            </Button>
          ) : null}
        </div>
        <div className="flex items-center justify-between gap-3">
          <p className="text-sm text-muted-ink tabular-nums" aria-live="polite">
            {summary}
          </p>
          <Button variant="ghost" size="sm" onClick={refresh}>
            <RotateCw aria-hidden="true" />
            Refresh
          </Button>
        </div>
      </div>
      {error ? (
        <p role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="h-[calc(100dvh-21rem)] min-h-[440px] w-full">
        <AgGridReact<DeskAssessmentRow>
          ref={gridRef}
          theme={seedGridTheme}
          rowModelType="infinite"
          datasource={datasource}
          cacheBlockSize={BLOCK_SIZE}
          maxConcurrentDatasourceRequests={1}
          maxBlocksInCache={20}
          columnDefs={columns}
          defaultColDef={defaultColDef}
          getRowId={(params) => params.data.assessmentId}
          onRowClicked={onRowClicked}
          onCellKeyDown={onCellKeyDown}
          noRowsOverlayComponent={NoRows}
          rowClass="cursor-pointer"
          enableCellTextSelection
          ensureDomOrder
        />
      </div>
      <p className="text-xs text-muted-ink">
        Enter or click opens the evidence beside the list. Sorting and filtering happen on the server, so they cover every
        fair check.
      </p>

      <Sheet open={selected !== null} onOpenChange={(open) => (open ? undefined : setSelected(null))}>
        <SheetContent side="right" className="w-full gap-0 sm:max-w-2xl">
          <SheetHeader>
            <SheetTitle className="font-display text-2xl font-light">Fair check</SheetTitle>
            <SheetDescription>
              Evidence for the officer, then your decision. Members see options in plain words, never a score.
            </SheetDescription>
          </SheetHeader>
          {selected ? <SheetBody key={selected} assessmentId={selected} token={token} onDecided={onDecided} /> : null}
        </SheetContent>
      </Sheet>
    </section>
  );
}
