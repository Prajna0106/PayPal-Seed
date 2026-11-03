import { Skeleton } from "@paypal-seed/ui/components/skeleton";

export default function ShopLoading() {
  return (
    <div className="flex flex-col gap-8" aria-busy="true" aria-label="Loading">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-28" />
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <Skeleton className="h-16 rounded-xl" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: 3 }, (_, i) => (
          <Skeleton key={i} className="h-80 rounded-2xl" />
        ))}
      </div>
    </div>
  );
}
