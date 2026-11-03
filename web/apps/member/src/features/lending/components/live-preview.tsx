"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { useEffect, useRef, useState } from "react";
import { type PreviewState, previewLoan } from "../actions";
import { previewNotice } from "../model";

export interface LivePreviewProps {
  amountDollars: number;
  term: number;
  /** A day from 1 to 28, or null to let the backend use its default. */
  dueDay: number | null;
  /** Called when the member takes the backend's suggested alternative. */
  onTry: (suggestion: { amountDollars: number; term: number }) => void;
}

const DEBOUNCE_MS = 350;

/**
 * The monthly payment for what the member has chosen so far. It asks the server a moment after the amount or the months
 * stop changing; the server asks the backend, which uses the same code as the real loan. Nothing is worked out here, and
 * nothing is saved. While an answer is on its way the last one stays, dimmed, so the page does not jump.
 */
export function LivePreview({ amountDollars, term, dueDay, onTry }: LivePreviewProps) {
  const key = `${amountDollars}|${term}|${dueDay ?? ""}`;
  const [answer, setAnswer] = useState<{ key: string; state: PreviewState } | null>(null);
  const first = useRef(true);

  useEffect(() => {
    // Only the newest request may show: a slow answer for an older choice must not overwrite a newer one.
    let current = true;
    const timer = setTimeout(
      () => {
        void previewLoan(amountDollars, term, dueDay).then((state) => {
          if (current) {
            setAnswer({ key, state });
          }
        });
      },
      first.current ? 0 : DEBOUNCE_MS,
    );
    first.current = false;
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [amountDollars, term, dueDay, key]);

  const busy = answer === null || answer.key !== key;
  const state = answer?.state ?? null;

  return (
    <section aria-labelledby="live-preview-title" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
      <h2 id="live-preview-title" className="text-title-sm font-medium text-ink">
        Your monthly payment
      </h2>
      <div aria-live="polite" aria-atomic="true" aria-busy={busy} className="flex min-h-24 flex-col gap-2">
        {state === null ? (
          <>
            <span className="sr-only">Working out your payment</span>
            <Skeleton className="h-10 w-44" />
            <Skeleton className="h-4 w-64 max-w-full" />
          </>
        ) : (
          <div className={busy ? "flex flex-col gap-2 opacity-60 transition-opacity motion-reduce:transition-none" : "flex flex-col gap-2"}>
            <PreviewBody state={state} onTry={onTry} />
            {busy ? <p className="text-sm text-muted-ink">Updating…</p> : null}
          </div>
        )}
      </div>
    </section>
  );
}

function PreviewBody({ state, onTry }: { state: PreviewState; onTry: LivePreviewProps["onTry"] }) {
  if (state.status === "error") {
    return (
      <p className="text-body text-pretty">
        {state.message} You can still carry on: your exact payment comes with your fair check.
      </p>
    );
  }
  const { preview } = state;
  const notice = previewNotice(preview);
  const stale = preview.incomeStale ? (
    <p className="text-sm text-muted-ink text-pretty">
      We could not refresh your PayPal history just now, so this uses what we saw earlier.
    </p>
  ) : null;
  if (notice) {
    return (
      <>
        <p className="text-body text-pretty">{notice.message}</p>
        {notice.suggestion ? (
          <Button
            type="button"
            variant="outline"
            className="min-h-11 self-start"
            onClick={() => notice.suggestion && onTry(notice.suggestion)}
          >
            Try {formatMoney(notice.suggestion.amountDollars * 100)} over {notice.suggestion.term} months
          </Button>
        ) : null}
        {stale}
      </>
    );
  }
  return (
    <>
      <p className="text-display-sm text-ink tabular-nums">
        {formatMoney(preview.monthlyInstalmentCents)} <span className="text-body">a month</span>
      </p>
      <p className="text-sm text-muted-ink text-pretty">
        {preview.termMonths} payments, 0% interest and no fees: you repay {formatMoney(preview.totalRepayableCents)} in all.
        {preview.firstDueDate ? ` First payment ${formatBusinessDate(preview.firstDueDate)}.` : ""}
      </p>
      {stale}
    </>
  );
}
