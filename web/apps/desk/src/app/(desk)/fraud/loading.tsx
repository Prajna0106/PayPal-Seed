import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function Loading() {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading fraud flags">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-9 w-1/3" />
      </div>
      <Skeleton className="h-20 rounded-lg" />
      <Skeleton className="h-[calc(100dvh-22rem)] min-h-[420px] rounded-2xl" />
    </div>
  );
}
