"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import dynamic from "next/dynamic";

/** AG Grid is browser-only; this keeps it out of the server bundle and holds its space while it loads. */
export const ApplicationsGridLoader = dynamic(() => import("./applications-grid").then((mod) => mod.ApplicationsGrid), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading the applications queue">
      <div className="flex flex-wrap items-center gap-2">
        <Skeleton className="h-9 w-40 rounded-full" />
        <Skeleton className="h-9 w-48 rounded-full" />
        <Skeleton className="h-9 w-32 rounded-full" />
      </div>
      <Skeleton className="h-[calc(100dvh-21rem)] min-h-[440px] w-full rounded-2xl" />
    </div>
  ),
});
