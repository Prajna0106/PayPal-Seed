"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import dynamic from "next/dynamic";

/** AG Grid is browser-only; this keeps it out of the server bundle and holds its space while it loads. */
export const QueueGridLoader = dynamic(() => import("./queue-grid").then((mod) => mod.QueueGrid), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading the queue">
      <div className="flex items-center justify-between">
        <Skeleton className="h-10 w-72 rounded-md" />
        <Skeleton className="h-8 w-56 rounded-full" />
      </div>
      <Skeleton className="h-[calc(100dvh-19rem)] min-h-[440px] w-full rounded-2xl" />
    </div>
  ),
});
