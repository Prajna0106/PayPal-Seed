"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import dynamic from "next/dynamic";

/** Bryntum is browser-only; the skeleton holds the chart's exact space (legend row plus 280 px of chart). */
export const WorkloadChartLoader = dynamic(() => import("@paypal-seed/timeline").then((mod) => mod.WorkloadScheduler), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading the workload chart">
      <Skeleton className="h-5 w-80 max-w-full" />
      <Skeleton className="h-[280px] w-full rounded-2xl" />
    </div>
  ),
});
