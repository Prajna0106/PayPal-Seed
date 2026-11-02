import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function Loading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-live="polite">
      <span className="sr-only">Loading your application</span>
      <Skeleton className="h-9 w-40 rounded-full" />
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-12 w-96 max-w-full" />
        <Skeleton className="h-5 w-2/3" />
      </div>
      <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <Skeleton className="h-72 rounded-2xl" />
        <Skeleton className="h-72 rounded-2xl" />
      </div>
    </div>
  );
}
