import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function GoalLoading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-label="Loading your goal">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-32 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-44 rounded-2xl" />
    </div>
  );
}
