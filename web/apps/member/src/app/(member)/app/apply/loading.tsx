import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function Loading() {
  return (
    <div className="flex max-w-3xl flex-col gap-8" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading</span>
      <Skeleton className="h-9 w-20 rounded-full" />
      <Skeleton className="h-10 w-full" />
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-12 w-3/4" />
        <Skeleton className="h-5 w-2/3" />
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        <Skeleton className="h-32 rounded-xl" />
        <Skeleton className="h-32 rounded-xl" />
        <Skeleton className="h-32 rounded-xl" />
      </div>
      <Skeleton className="h-28 rounded-2xl" />
      <Skeleton className="h-12 w-48 rounded-full" />
    </div>
  );
}
