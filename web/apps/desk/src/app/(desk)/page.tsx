import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowUpRight } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import {
  AttentionSection,
  CobSection,
  KpiSection,
  KpiSkeleton,
  PanelSkeleton,
  StatusSection,
} from "@/features/overview/components/sections";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Overview" };

export default async function OverviewPage() {
  const session = await requireSession();
  const firstName = session.name.split(" ")[0];
  return (
    <>
      <PageHeader
        eyebrow="Today"
        title={`Good to see you, ${firstName}`}
        description="The safety net at a glance: who needs a decision, what the Guardian is doing, and how the daily close went."
        actions={
          <Button asChild>
            <Link href="/hardship">
              Open the queue
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </Button>
        }
      />
      <Suspense fallback={<KpiSkeleton />}>
        <KpiSection />
      </Suspense>
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
        <Suspense fallback={<PanelSkeleton />}>
          <StatusSection />
        </Suspense>
        <Suspense fallback={<PanelSkeleton />}>
          <AttentionSection />
        </Suspense>
      </div>
      <Suspense fallback={<PanelSkeleton height="h-48" />}>
        <CobSection />
      </Suspense>
    </>
  );
}
