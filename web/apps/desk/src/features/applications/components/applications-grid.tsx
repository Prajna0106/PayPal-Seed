"use client";

import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
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
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { seedGridTheme } from "../lib/grid-theme";
import { createKeysetDatasource, type KeysetPage } from "../lib/keyset-datasource";
import {
  APPLICATION_STATUSES,
  type ApplicationFilters,
  type DeskApplicationRow,
  OUTCOMES,
  shortRef,
  SLA_STATES,
} from "../model";
import { QueueToolbar } from "./queue-toolbar";

ModuleRegistry.registerModules([AllCommunityModule]);

/** AG Grid's block size and the backend page size are the same number: one block is one keyset page. */
const BLOCK_SIZE = 50;

type Params = ICellRendererParams<DeskApplicationRow>;

const Muted = () => <span className="text-muted-ink">—</span>;

/** An unloaded row has no data yet: hold its space with a quiet bar. */
function Pending() {
  return <span aria-hidden="true" className="inline-block h-3 w-20 animate-pulse rounded-full bg-surface-strong" />;
}

function MemberCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  return (
    <Link
      href={`/applications/${data.applicationId}` as Route}
      className="font-mono text-xs text-ink underline-offset-4 hover:underline"
      tabIndex={-1}
      prefetch={false}
    >
      {shortRef(data.memberRef)}
    </Link>
  );
}

function StatusCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  const status = APPLICATION_STATUSES[data.status];
  return <StatusPill tone={status.tone}>{status.label}</StatusPill>;
}

function OutcomeCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  const outcome = data.assessmentOutcome ? OUTCOMES[data.assessmentOutcome] : undefined;
  return outcome ? (
    <StatusPill tone={outcome.tone} dot={false}>
      {outcome.label}
    </StatusPill>
  ) : (
    <Muted />
  );
}

function SlaCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  if (data.slaState === "NO_SLA") {
    return <Muted />;
  }
  const badge = SLA_STATES[data.slaState];
  return (
    <span className="inline-flex items-center gap-2">
      <span className="tabular-nums">{formatDateTime(data.slaDueAt, { dateStyle: "short" })}</span>
      {badge ? (
        <StatusPill tone={badge.tone} dot={false}>
          {badge.label}
        </StatusPill>
      ) : null}
    </span>
  );
}

function OfficerCell({ data }: Params) {
  if (!data) {
    return <Pending />;
  }
  return data.assignedOfficerRef ? (
    <span className="font-mono text-xs">{data.assignedOfficerRef.slice(0, 8)}</span>
  ) : (
    <span className="text-muted-ink">Unassigned</span>
  );
}

function NoRows() {
  return (
    <div role="status" className="flex flex-col items-center gap-1 text-center">
      <p className="font-display text-xl font-light text-ink">No applications match</p>
      <p className="text-sm text-muted-ink">Change the filters, or check again after members apply.</p>
    </div>
  );
}

export interface ApplicationsGridProps {
  initialFilters: ApplicationFilters;
}

/**
 * The loan application queue on AG Grid's infinite row model. Each block is one keyset page from the backend, sorted
 * and filtered there; the grid never holds or sorts the whole list. Filters live in the URL so a view can be shared.
 */
export function ApplicationsGrid({ initialFilters }: ApplicationsGridProps) {
  const router = useRouter();
  const gridRef = useRef<AgGridReact<DeskApplicationRow>>(null);
  const [filters, setFilters] = useState(initialFilters);
  const appliedFilters = useRef(initialFilters);
  const [summary, setSummary] = useState("Loading…");
  const [error, setError] = useState<string | null>(null);

  const [datasource] = useState(() =>
    createKeysetDatasource<DeskApplicationRow, ApplicationFilters>({
      endpoint: "/api/applications",
      initialFilters,
      onFirstPage: (page) => {
        const { total } = page as KeysetPage<DeskApplicationRow>;
        if (!total) {
          return;
        }
        const count = total.value.toLocaleString("en-US");
        setSummary(`${total.exact ? "" : "About "}${count} application${total.value === 1 ? "" : "s"}`);
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
    if (filters.slaBreached) {
      params.set("slaBreached", "true");
    }
    const query = params.toString();
    window.history.replaceState(null, "", query ? `?${query}` : window.location.pathname);
  }, [filters, datasource]);

  const refresh = useCallback(() => {
    datasource.reset();
    setError(null);
    gridRef.current?.api?.purgeInfiniteCache();
  }, [datasource]);

  const columns = useMemo<ColDef<DeskApplicationRow>[]>(
    () => [
      { colId: "member", headerName: "Member", cellRenderer: MemberCell, width: 120, sortable: false, pinned: "left" },
      {
        colId: "requestedAmountCents",
        field: "requestedAmountCents",
        headerName: "Amount",
        valueFormatter: ({ value }) => (typeof value === "number" ? formatMoney(value) : ""),
        type: "rightAligned",
        width: 120,
      },
      {
        colId: "termMonths",
        field: "termMonths",
        headerName: "Term",
        valueFormatter: ({ value }) => (typeof value === "number" ? `${value} mo` : ""),
        type: "rightAligned",
        width: 100,
      },
      { colId: "status", field: "status", headerName: "Status", cellRenderer: StatusCell, width: 220 },
      {
        colId: "savingStreakMonths",
        field: "savingStreakMonths",
        headerName: "Saving streak",
        valueFormatter: ({ value }) => (typeof value === "number" ? `${value} mo` : ""),
        type: "rightAligned",
        width: 140,
      },
      {
        colId: "assessmentOutcome",
        field: "assessmentOutcome",
        headerName: "Fair check",
        cellRenderer: OutcomeCell,
        width: 190,
      },
      {
        colId: "age",
        field: "ageDays",
        headerName: "Age",
        valueFormatter: ({ value }) => (typeof value === "number" ? `${value} d` : ""),
        type: "rightAligned",
        width: 90,
      },
      { colId: "slaDueAt", field: "slaDueAt", headerName: "SLA due", cellRenderer: SlaCell, width: 210, sort: "asc" },
      {
        colId: "assignedOfficer",
        field: "assignedOfficerRef",
        headerName: "Officer",
        cellRenderer: OfficerCell,
        flex: 1,
        minWidth: 140,
      },
    ],
    [],
  );

  const defaultColDef = useMemo<ColDef<DeskApplicationRow>>(() => ({ sortable: true, resizable: true }), []);

  const open = useCallback(
    (applicationId: string | undefined) => {
      if (applicationId) {
        router.push(`/applications/${applicationId}` as Route);
      }
    },
    [router],
  );

  const onRowClicked = useCallback(
    (event: RowClickedEvent<DeskApplicationRow>) => open(event.data?.applicationId),
    [open],
  );
  const onCellKeyDown = useCallback(
    (event: CellKeyDownEvent<DeskApplicationRow>) => {
      if ((event.event as KeyboardEvent | null)?.key === "Enter") {
        open(event.data?.applicationId);
      }
    },
    [open],
  );

  return (
    <section aria-label="Loan applications" className="flex flex-col gap-3">
      <QueueToolbar filters={filters} onChange={setFilters} onRefresh={refresh} summary={summary} />
      {error ? (
        <p role="alert" className="rounded-lg border border-danger/30 bg-danger-soft px-4 py-3 text-sm text-danger">
          {error}
        </p>
      ) : null}
      <div className="h-[calc(100dvh-21rem)] min-h-[440px] w-full">
        <AgGridReact<DeskApplicationRow>
          ref={gridRef}
          theme={seedGridTheme}
          rowModelType="infinite"
          datasource={datasource}
          cacheBlockSize={BLOCK_SIZE}
          maxConcurrentDatasourceRequests={1}
          maxBlocksInCache={20}
          columnDefs={columns}
          defaultColDef={defaultColDef}
          getRowId={(params) => params.data.applicationId}
          onRowClicked={onRowClicked}
          onCellKeyDown={onCellKeyDown}
          noRowsOverlayComponent={NoRows}
          rowClass="cursor-pointer"
          enableCellTextSelection
          ensureDomOrder
        />
      </div>
      <p className="text-xs text-muted-ink">
        Enter or click opens an application. Sorting and filtering happen on the server, so they cover every application,
        not only the rows on screen.
      </p>
    </section>
  );
}
