"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { cn } from "@paypal-seed/ui/lib/utils";
import { RotateCw } from "lucide-react";
import { APPLICATION_STATUSES, type ApplicationFilters, type ApplicationStatus, NO_FILTERS, STATUS_ORDER } from "../model";

export function FilterChip({ pressed, onClick, children }: { pressed: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        "inline-flex h-11 items-center rounded-full border px-4 text-sm whitespace-nowrap transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink sm:h-9",
        pressed ? "border-ink bg-ink text-white" : "border-hairline bg-surface text-ink hover:bg-canvas-soft",
      )}
    >
      {children}
    </button>
  );
}

export interface QueueToolbarProps {
  filters: ApplicationFilters;
  onChange: (filters: ApplicationFilters) => void;
  onRefresh: () => void;
  summary: string;
}

/** Status and "SLA breached" filters. They narrow the list on the server; the grid reloads from the first page. */
export function QueueToolbar({ filters, onChange, onRefresh, summary }: QueueToolbarProps) {
  const toggleStatus = (status: ApplicationStatus) =>
    onChange({
      ...filters,
      status: filters.status.includes(status) ? filters.status.filter((s) => s !== status) : [...filters.status, status],
    });
  const filtered = filters.status.length > 0 || filters.slaBreached;

  return (
    <div className="flex flex-col gap-3">
      <div role="group" aria-label="Filter applications" className="flex flex-wrap items-center gap-2">
        {STATUS_ORDER.map((status) => (
          <FilterChip key={status} pressed={filters.status.includes(status)} onClick={() => toggleStatus(status)}>
            {APPLICATION_STATUSES[status].label}
          </FilterChip>
        ))}
        <span aria-hidden="true" className="mx-1 hidden h-6 w-px bg-hairline sm:block" />
        <FilterChip pressed={filters.slaBreached} onClick={() => onChange({ ...filters, slaBreached: !filters.slaBreached })}>
          SLA breached
        </FilterChip>
        {filtered ? (
          <Button variant="ghost" size="sm" onClick={() => onChange(NO_FILTERS)}>
            Clear filters
          </Button>
        ) : null}
      </div>
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-muted-ink tabular-nums" aria-live="polite">
          {summary}
        </p>
        <Button variant="ghost" size="sm" onClick={onRefresh}>
          <RotateCw aria-hidden="true" />
          Refresh
        </Button>
      </div>
    </div>
  );
}
