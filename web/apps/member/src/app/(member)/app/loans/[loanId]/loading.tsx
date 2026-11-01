import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function LoanLoading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-label="Loading your plan">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-24 rounded-2xl" />
        ))}
      </div>
      <Skeleton className="h-[196px] rounded-2xl" />
      <Skeleton className="h-72 rounded-2xl" />
    </div>
  );
}
