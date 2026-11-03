"use client";

import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Button } from "@paypal-seed/ui/components/button";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import { useState, useTransition } from "react";
import { loadMoreHistory } from "../actions";
import { describeFact, type HistoryItem } from "../model";

/** The facts behind the record, newest first. The server pages by keyset; this only asks for the next page. */
export function HistoryList({ initial, nextBefore }: { initial: HistoryItem[]; nextBefore: number | null }) {
  const [items, setItems] = useState(initial);
  const [cursor, setCursor] = useState(nextBefore);
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  if (items.length === 0) {
    return (
      <EmptyState
        title="Nothing here yet"
        description="When you save or pay on a loan, each step shows up here."
      />
    );
  }

  const more = () => {
    if (cursor === null) {
      return;
    }
    const before = cursor;
    startTransition(async () => {
      const page = await loadMoreHistory(before);
      if (page.ok) {
        setItems((current) => [...current, ...page.items]);
        setCursor(page.nextBefore);
        setError(null);
      } else {
        setError(page.message);
      }
    });
  };

  return (
    <div className="flex flex-col gap-4">
      <ul className="divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
        {items.map((item) => (
          <li key={item.seq} className="flex flex-col gap-0.5 px-5 py-3.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
            <span className="text-sm text-ink">{describeFact(item)}</span>
            <time dateTime={item.occurredOn} className="text-sm text-muted-ink">
              {formatBusinessDate(item.occurredOn, "medium")}
            </time>
          </li>
        ))}
      </ul>
      <div aria-live="polite" className="flex flex-col items-start gap-2">
        {error ? <p className="text-sm text-danger">{error}</p> : null}
        {cursor !== null ? (
          <Button variant="outline" onClick={more} disabled={pending} aria-busy={pending}>
            {pending ? "Loading..." : "Show older"}
          </Button>
        ) : null}
      </div>
    </div>
  );
}
