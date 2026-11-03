import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowUpRight, Clock, HandCoins } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { staffDirectory } from "@/features/applications/server/queries";
import { COVER_FILTERS, COVER_STATUSES, type CoverFilter, type CoverProgress as CoverProgressModel, coverProgress, REASON_LABELS, reserveErrorMessage } from "../model";
import { newIdempotencyKey } from "../server/keys";
import { type CoverRequest, coverPage } from "../server/queries";
import { CoverProgress } from "./cover-progress";
import { DecideDialog } from "./decide-dialog";
import { SectionError } from "./section-error";

const PAGE_SIZE = 25;

export function CoverListSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading cover requests">
      {Array.from({ length: 3 }, (_, i) => (
        <Skeleton key={i} className="h-28 rounded-xl" />
      ))}
    </div>
  );
}

function CoverRow({ cover, canDecide, staff, asOf }: { cover: CoverRequest; canDecide: boolean; staff: Map<string, string>; asOf: number }) {
  const status = COVER_STATUSES[cover.status];
  const open = cover.status === "PENDING_APPROVAL";
  const progress = coverProgress(cover, (id) => staff.get(id) ?? `Officer ${id.slice(0, 8)}`, asOf);
  return (
    <li className="flex flex-col gap-4 rounded-xl border border-hairline bg-surface p-4 sm:p-5 md:flex-row md:items-start md:justify-between">
      <div className="flex min-w-0 flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <p className="font-display text-2xl font-light text-ink tabular-nums">{formatMoney(cover.amountCents)}</p>
          <StatusPill tone={status.tone}>{status.label}</StatusPill>
          <span className="inline-flex items-center gap-1 text-xs text-muted-ink [&_svg]:size-3.5">
            <Clock aria-hidden="true" />
            Requested {formatBusinessDate(cover.requestedOn)}
          </span>
        </div>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2 xl:grid-cols-4">
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Loan</dt>
            <dd>
              <Link
                href={`/loans/${cover.loanId}` as Route}
                className="inline-flex items-center gap-1 font-mono text-xs text-ink underline-offset-4 hover:underline"
              >
                {cover.loanId.slice(0, 8)}
                <ArrowUpRight aria-hidden="true" className="size-3" />
              </Link>
            </dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Member</dt>
            <dd className="font-mono text-xs text-ink">{cover.memberId.slice(0, 8)}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Reason</dt>
            <dd className="text-ink">{REASON_LABELS[cover.reasonCode] ?? cover.reasonCode}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">Hardship case</dt>
            <dd>
              {cover.hardshipCaseId ? (
                <Link href={`/hardship/${cover.hardshipCaseId}` as Route} className="font-mono text-xs text-ink underline-offset-4 hover:underline">
                  {cover.hardshipCaseId.slice(0, 8)}
                </Link>
              ) : (
                <span className="text-muted-ink">None</span>
              )}
            </dd>
          </div>
        </dl>
        <CoverProgress progress={progress} />
        {cover.closedReason ? <p className="text-xs text-muted-ink">Closed: {cover.closedReason}</p> : null}
      </div>
      {open && canDecide ? <DecideDialogSlot cover={cover} progress={progress} /> : null}
    </li>
  );
}

function DecideDialogSlot({ cover, progress }: { cover: CoverRequest; progress: CoverProgressModel }) {
  return (
    <div className="shrink-0">
      <DecideDialog
        coverRequestId={cover.id}
        amountLabel={formatMoney(cover.amountCents)}
        idempotencyKey={newIdempotencyKey()}
        progress={progress}
      />
    </div>
  );
}

function FilterLinks({ filter }: { filter: CoverFilter }) {
  return (
    <nav aria-label="Filter cover requests" className="flex flex-wrap gap-2">
      {(Object.entries(COVER_FILTERS) as [CoverFilter, (typeof COVER_FILTERS)[CoverFilter]][]).map(([value, item]) => (
        <Button key={value} asChild size="sm" variant={value === filter ? "default" : "outline"} className="min-h-11 sm:min-h-8">
          <Link
            href={(value === "all" ? "/reserve" : `/reserve?status=${value}`) as Route}
            aria-current={value === filter ? "page" : undefined}
            scroll={false}
          >
            {item.label}
          </Link>
        </Button>
      ))}
    </nav>
  );
}

/** Cover requests, newest first. A decision needs a maker, then a different checker; the backend enforces it. */
export async function CoverList({ filter, before, canDecide }: { filter: CoverFilter; before: string | undefined; canDecide: boolean }) {
  let page: Awaited<ReturnType<typeof coverPage>>;
  let staff: Map<string, string>;
  try {
    // Names are a courtesy and never fail the page: the directory read swallows its own errors.
    [page, staff] = await Promise.all([coverPage(COVER_FILTERS[filter].statuses, before, PAGE_SIZE), staffDirectory()]);
  } catch (error) {
    return <SectionError title="Cover requests could not be loaded" message={reserveErrorMessage(error)} />;
  }
  const filterHref = filter === "all" ? "/reserve" : `/reserve?status=${filter}`;
  return (
    <section aria-label="Cover requests" className="flex flex-col gap-4">
      <FilterLinks filter={filter} />
      {page.rows.length === 0 ? (
        <EmptyState
          icon={<HandCoins aria-hidden="true" />}
          title={before ? "No more cover requests" : filter === "all" ? "No cover requests yet" : "Nothing here"}
          description={
            before
              ? "That was the last page."
              : filter === "all"
                ? "When an officer asks the fund to pay part of a member's loan, it shows up here for a second officer to decide."
                : "No cover requests match this filter."
          }
          action={
            before ? (
              <Button asChild variant="outline" size="sm">
                <Link href={filterHref as Route}>Back to newest</Link>
              </Button>
            ) : undefined
          }
        />
      ) : (
        <ul className="flex flex-col gap-3">
          {page.rows.map((cover) => (
            <CoverRow key={cover.id} cover={cover} canDecide={canDecide} staff={staff} asOf={page.asOf} />
          ))}
        </ul>
      )}
      {page.nextBefore ? (
        <div className="flex justify-center">
          <Button asChild variant="outline" className="min-h-11">
            <Link href={`${filterHref}${filterHref.includes("?") ? "&" : "?"}before=${page.nextBefore}` as Route} scroll={false}>
              Older requests
            </Link>
          </Button>
        </div>
      ) : null}
    </section>
  );
}
