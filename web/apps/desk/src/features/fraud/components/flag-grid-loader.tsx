"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import dynamic from "next/dynamic";

/** AG Grid is browser-only; this keeps it out of the server bundle and holds its space while it loads. */
export const FlagGridLoader = dynamic(() => import("./flag-grid").then((mod) => mod.FlagGrid), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading the flags">
      <div className="flex items-center justify-between">
        <Skeleton className="h-5 w-56" />
        <Skeleton className="h-8 w-24 rounded-full" />
      </div>
      <Skeleton className="h-[calc(100dvh-22rem)] min-h-[420px] w-full rounded-2xl" />
    </div>
  ),
});
