import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function ShopGoalsLoading() {
  return (
    <div className="flex flex-col gap-8" aria-busy="true" aria-label="Loading your product goals">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <Skeleton className="h-48 rounded-2xl" />
      <Skeleton className="h-48 rounded-2xl" />
    </div>
  );
}
