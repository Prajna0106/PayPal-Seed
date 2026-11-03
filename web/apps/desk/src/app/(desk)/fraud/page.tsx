import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { AdvisoryNote } from "@/features/fraud/components/advisory-note";
import { FlagGridLoader } from "@/features/fraud/components/flag-grid-loader";
import { FLAG_FILTERS, type FlagFilter, readFlagFilter } from "@/features/fraud/model";

export const metadata: Metadata = { title: "Fraud flags" };

export default async function FraudPage({ searchParams }: PageProps<"/fraud">) {
  const filter = readFlagFilter((await searchParams).status);
  const query = new URLSearchParams();
  for (const status of FLAG_FILTERS[filter].statuses) {
    query.append("status", status);
  }
  return (
    <>
      <PageHeader
        eyebrow="Integrity"
        title="Fraud flags"
        description="Members whose activity adds up to the threshold. An officer looks at the evidence and clears the flag or confirms it."
        actions={
          <nav aria-label="Filter flags" className="flex flex-wrap gap-2">
            {(Object.entries(FLAG_FILTERS) as [FlagFilter, (typeof FLAG_FILTERS)[FlagFilter]][]).map(([value, item]) => (
              <Button key={value} asChild size="sm" variant={value === filter ? "default" : "outline"} className="min-h-11 sm:min-h-8">
                <Link href={(value === "open" ? "/fraud" : `/fraud?status=${value}`) as Route} aria-current={value === filter ? "page" : undefined}>
                  {item.label}
                </Link>
              </Button>
            ))}
          </nav>
        }
      />
      <AdvisoryNote />
      <FlagGridLoader key={filter} query={query.toString()} />
    </>
  );
}
