"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import type { JourneyLabels, JourneyPayload } from "@paypal-seed/timeline";
import dynamic from "next/dynamic";
import { useMemo } from "react";

// Room for the axis and three rows of steps (saving, loan opens, loan payments); more rows scroll inside the chart.
const HEIGHT = 280;

// Bryntum touches the DOM on import, so it only ever loads in the browser, inside a skeleton of the same size.
const JourneyTimeline = dynamic(() => import("@paypal-seed/timeline").then((m) => m.JourneyTimeline), {
  ssr: false,
  loading: () => <Skeleton className="w-full rounded-2xl" style={{ height: HEIGHT }} aria-label="Loading your journey" />,
});

/** The journey chart. The page shows the same steps as a list, so the chart adds no second list for screen readers. */
export function JourneyChart({ payload, today }: { payload: JourneyPayload; today: string }) {
  const labels = useMemo<JourneyLabels>(
    () => ({
      title: "Your journey from saving to a loan",
      today: "Today",
      formatDate: (iso) => formatBusinessDate(iso, "long"),
    }),
    [],
  );
  return <JourneyTimeline payload={payload} today={today} labels={labels} height={HEIGHT} listForScreenReaders={false} />;
}
