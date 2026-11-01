"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import type { TimelineLabels, TimelinePayload } from "@paypal-seed/timeline/types";
import dynamic from "next/dynamic";
import { useMemo } from "react";
import { TIMELINE_STATUS_LABELS } from "../status";

const HEIGHT = 196;

// Bryntum touches the DOM on import, so it only ever loads in the browser, inside a skeleton of the same size.
const RepaymentTimeline = dynamic(() => import("@paypal-seed/timeline").then((m) => m.RepaymentTimeline), {
  ssr: false,
  loading: () => <Skeleton className="w-full rounded-2xl" style={{ height: HEIGHT }} aria-label="Loading your timeline" />,
});

export function PlanTimeline({ payload, today }: { payload: TimelinePayload; today: string }) {
  const labels = useMemo<TimelineLabels>(
    () => ({
      title: "Your payments over time",
      currentPlan: "Your plan now",
      previousPlan: "Before the change",
      today: "Today",
      status: TIMELINE_STATUS_LABELS,
      formatDate: (iso) => formatBusinessDate(iso, "long"),
    }),
    [],
  );
  return <RepaymentTimeline payload={payload} today={today} labels={labels} height={HEIGHT} />;
}
