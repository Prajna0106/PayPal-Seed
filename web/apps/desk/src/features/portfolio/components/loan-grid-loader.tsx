"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import dynamic from "next/dynamic";

/** AG Grid is browser-only; this keeps it out of the server bundle and holds the grid's space while it loads. */
export const LoanGridLoader = dynamic(() => import("./loan-grid").then((mod) => mod.LoanGrid), {
  ssr: false,
  loading: () => (
    <div className="flex flex-col gap-3" aria-busy="true" aria-label="Loading the loans">
      <Skeleton className="h-5 w-64" />
      <Skeleton className="h-[560px] w-full rounded-2xl" />
    </div>
  ),
});
