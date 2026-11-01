"use client";

import { Select, SelectContent, SelectGroup, SelectItem, SelectLabel, SelectSeparator, SelectTrigger, SelectValue } from "@paypal-seed/ui/components/select";
import type { Route } from "next";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useTransition } from "react";
import { STATUS_PRESETS, type QueueSearch } from "../filters";
import { CASE_STATUSES, ORIGINS, RISK_LEVELS, STATUS_ORDER } from "../model";

/** Status, level and origin filters. They write the URL; the server page reads it and reloads the grid. */
export function QueueFilters({ search }: { search: QueueSearch }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const [pending, startTransition] = useTransition();

  const update = (key: keyof QueueSearch, value: string) => {
    const next = new URLSearchParams(params.toString());
    const isDefault = (key === "status" && value === "open") || value === "any";
    if (isDefault) {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    const query = next.toString();
    startTransition(() => router.replace((query ? `${pathname}?${query}` : pathname) as Route, { scroll: false }));
  };

  return (
    <div className="flex flex-wrap items-center gap-2" aria-busy={pending}>
      <Select value={search.status} onValueChange={(value) => update("status", value)}>
        <SelectTrigger className="w-44" aria-label="Status">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            {Object.entries(STATUS_PRESETS).map(([value, preset]) => (
              <SelectItem key={value} value={value}>
                {preset.label}
              </SelectItem>
            ))}
          </SelectGroup>
          <SelectSeparator />
          <SelectGroup>
            <SelectLabel>Status</SelectLabel>
            {STATUS_ORDER.map((status) => (
              <SelectItem key={status} value={status}>
                {CASE_STATUSES[status].label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      <Select value={search.level} onValueChange={(value) => update("level", value)}>
        <SelectTrigger className="w-40" aria-label="Risk level">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="any">Any level</SelectItem>
          {Object.entries(RISK_LEVELS).map(([value, level]) => (
            <SelectItem key={value} value={value}>
              {level.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Select value={search.origin} onValueChange={(value) => update("origin", value)}>
        <SelectTrigger className="w-40" aria-label="Origin">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="any">Any origin</SelectItem>
          {Object.entries(ORIGINS).map(([value, label]) => (
            <SelectItem key={value} value={value}>
              {label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
