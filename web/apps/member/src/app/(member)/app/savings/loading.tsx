import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function SavingsLoading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-label="Loading your savings">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <Skeleton className="h-32 rounded-xl" />
        <Skeleton className="h-32 rounded-xl" />
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <Skeleton className="h-64 rounded-2xl" />
        <Skeleton className="h-64 rounded-2xl" />
      </div>
    </div>
  );
}
