import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { History } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { dateInProgramZone } from "@/features/savings/model";
import { ACTIVITY_WORDS } from "../copy";
import type { ActivityItem } from "../model";
import { SectionCard } from "./section-card";

/** Where an activity row leads: the goal or the loan it belongs to, when the backend names one. */
function activityHref(item: ActivityItem): Route | null {
  if (item.goalId) {
    return `/app/savings/${item.goalId}` as Route;
  }
  if (item.loanId) {
    return `/app/loans/${item.loanId}` as Route;
  }
  return null;
}

/**
 * The latest things that happened, newest first, in plain words. Amounts and times are the backend's; the date is the
 * day in the program's time zone, because a loan event only knows its day.
 */
export function ActivityCard({ items }: { items: ActivityItem[] }) {
  return (
    <SectionCard title="Recent activity" icon={<History aria-hidden="true" />}>
      {items.length === 0 ? (
        <p className="text-sm text-body text-pretty">Nothing yet. Your deposits and payments will show up here.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-hairline">
          {items.map((item, index) => {
            const words = ACTIVITY_WORDS[item.kind];
            const href = activityHref(item);
            const when = formatBusinessDate(dateInProgramZone(item.occurredAt), "medium");
            const body = (
              <>
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="text-sm font-medium text-ink text-pretty">{words.title}</span>
                  <span className="text-xs text-muted-ink">
                    {when}
                    {words.detail ? ` · ${words.detail}` : ""}
                  </span>
                </span>
                <span className="shrink-0 text-sm font-medium text-ink tabular-nums">{formatMoney(item.amount.cents)}</span>
              </>
            );
            const row = "flex min-h-11 items-center justify-between gap-4 py-2";
            return (
              <li key={`${item.kind}-${item.occurredAt}-${index}`}>
                {href ? (
                  <Link
                    href={href}
                    className={`${row} rounded-lg outline-none hover:bg-canvas-soft focus-visible:ring-[3px] focus-visible:ring-ring/50`}
                  >
                    {body}
                  </Link>
                ) : (
                  <div className={row}>{body}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </SectionCard>
  );
}
