import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function Loading() {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label="Loading applications">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-9 w-1/2" />
      </div>
      <Skeleton className="h-9 w-2/3 rounded-full" />
      <Skeleton className="h-[calc(100dvh-21rem)] min-h-[440px] w-full rounded-2xl" />
    </div>
  );
}
