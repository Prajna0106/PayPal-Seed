import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function Loading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading your loans</span>
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-12 w-64" />
        <Skeleton className="h-5 w-96 max-w-full" />
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <Skeleton className="h-60 rounded-2xl" />
        <Skeleton className="h-60 rounded-2xl" />
      </div>
    </div>
  );
}
