import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function HelpLoading() {
  return (
    <div className="flex flex-col gap-10" aria-busy="true" aria-label="Loading your options">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-10 w-2/3" />
      </div>
      <Skeleton className="h-40 rounded-3xl" />
      <div className="grid gap-4 md:grid-cols-2">
        {Array.from({ length: 4 }, (_, i) => (
          <Skeleton key={i} className="h-64 rounded-2xl" />
        ))}
      </div>
    </div>
  );
}
