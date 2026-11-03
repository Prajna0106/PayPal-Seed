"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { Route } from "next";
import { useId } from "react";
import { type LoanSearch } from "../filters";
import { AGING_BUCKETS, BUCKET_ORDER, LOAN_STATUSES, STATUS_ORDER } from "../model";

const selectClass =
  "h-11 min-w-36 rounded-md border border-hairline-strong bg-surface px-3 text-sm text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ink";

function Field({
  label,
  name,
  value,
  options,
  onChange,
}: {
  label: string;
  name: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (name: string, value: string) => void;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-xs font-medium text-muted-ink">
        {label}
      </label>
      <select id={id} className={selectClass} value={value} onChange={(event) => onChange(name, event.target.value)}>
        <option value="any">Any</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

const FLAG = [
  { value: "true", label: "Yes" },
  { value: "false", label: "No" },
];

/** Filters write to the URL; the page re-renders the grid for the new filter. */
export function LoanFilters({ search }: { search: LoanSearch }) {
  const router = useRouter();
  const pathname = usePathname();
  const current = useSearchParams();

  const change = (name: string, value: string) => {
    const next = new URLSearchParams(current.toString());
    if (value === "any") {
      next.delete(name);
    } else {
      next.set(name, value);
    }
    const query = next.toString();
    router.replace((query ? `${pathname}?${query}` : pathname) as Route, { scroll: false });
  };

  return (
    <form className="flex flex-wrap items-end gap-3" aria-label="Loan filters" onSubmit={(event) => event.preventDefault()}>
      <Field
        label="Status"
        name="status"
        value={search.status}
        options={STATUS_ORDER.map((s) => ({ value: s, label: LOAN_STATUSES[s].label }))}
        onChange={change}
      />
      <Field
        label="Aging"
        name="aging"
        value={search.aging}
        options={BUCKET_ORDER.map((b) => ({ value: b, label: AGING_BUCKETS[b].label }))}
        onChange={change}
      />
      <Field label="Autopay" name="autopay" value={search.autopay} options={FLAG} onChange={change} />
      <Field label="Open hardship case" name="hardship" value={search.hardship} options={FLAG} onChange={change} />
    </form>
  );
}
