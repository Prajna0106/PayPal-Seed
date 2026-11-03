import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { Results } from "@/features/shop/components/results";
import { SearchForm } from "@/features/shop/components/search-form";

export const metadata: Metadata = { title: "Save for something" };

export default async function ShopPage({ searchParams }: PageProps<"/app/shop">) {
  const { q } = await searchParams;
  const query = (typeof q === "string" ? q : "").trim().slice(0, 120);

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        eyebrow="Save for something"
        title="Something you would like to have"
        description="Find it, see the price, and save for it a little at a time. There is no hurry, and you decide whether to buy."
        actions={
          <Button asChild variant="outline">
            <Link href={"/app/shop/goals" as Route}>Your product goals</Link>
          </Button>
        }
      />
      <SearchForm query={query} />
      <Suspense
        key={query}
        fallback={
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-busy="true" aria-label="Loading products">
            {Array.from({ length: 6 }, (_, i) => (
              <Skeleton key={i} className="h-80 rounded-2xl" />
            ))}
          </div>
        }
      >
        <Results query={query} />
      </Suspense>
    </div>
  );
}
