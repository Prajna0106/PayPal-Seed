"use client";

import type { TimelineLabels, TimelinePayload } from "@paypal-seed/timeline";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import dynamic from "next/dynamic";

const RepaymentTimeline = dynamic(() => import("@paypal-seed/timeline").then((mod) => mod.RepaymentTimeline), {
  ssr: false,
  loading: () => <Skeleton className="h-[196px] w-full rounded-xl" aria-label="Loading the timeline" />,
});

const LABELS: TimelineLabels = {
  title: "Repayment plan timeline",
  currentPlan: "Current plan",
  previousPlan: "Previous plan",
  today: "Business date",
  status: {
    SCHEDULED: "Scheduled",
    DUE: "Due",
    PARTIALLY_PAID: "Partly paid",
    IN_GRACE: "In grace",
    LATE: "Late",
    PAID: "Paid",
    PAID_LATE: "Paid late",
    PREPAID: "Paid early",
    PREVIOUS_PLAN: "Previous plan",
  },
  formatDate: (iso) => formatBusinessDate(iso),
};

/** Bryntum Scheduler Pro (browser-only) with the desk's labels. */
export function DeskTimeline({ payload, today }: { payload: TimelinePayload; today: string }) {
  return <RepaymentTimeline payload={payload} today={today} labels={LABELS} height={196} />;
}
