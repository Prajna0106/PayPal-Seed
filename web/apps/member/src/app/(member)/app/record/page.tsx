import { isLocale, type Locale } from "@paypal-seed/i18n";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { type ReactNode, Suspense } from "react";
import { HistoryList } from "@/features/record/components/history-list";
import { HardshipNote, NextLevel, RecordSummary } from "@/features/record/components/record-summary";
import { ShareList } from "@/features/record/components/share-list";
import { SharePanel } from "@/features/record/components/share-panel";
import { TrustExplanation, TrustExplanationSkeleton } from "@/features/record/components/trust-explanation";
import { trustWords } from "@/features/record/copy";
import { getRecordPage } from "@/features/record/server/queries";

export const metadata: Metadata = { title: "My record" };

export default async function RecordPage({ searchParams }: PageProps<"/app/record">) {
  const query = await searchParams;
  const locale: Locale = isLocale(query.lang) ? query.lang : "en";
  const { record, history, shares, sharingAllowed } = await getRecordPage();

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 self-start">
        <Link href="/app">
          <ArrowLeft aria-hidden="true" />
          Home
        </Link>
      </Button>

      <PageHeader
        eyebrow="My record"
        title="Your Seed record"
        description="Saving on time and repaying on plan builds a record you can take with you. It is a level, never a score."
      />

      <RecordSummary record={record} />

      <Suspense fallback={<TrustExplanationSkeleton label={trustWords(locale).loading} />}>
        <TrustExplanation locale={locale} />
      </Suspense>

      <Section title="What comes next">
        <NextLevel record={record} />
        <HardshipNote plans={record.hardshipPlansOnPlan} />
      </Section>

      <Section title="Share your record" description="Show another lender or landlord where you stand, only if you want to.">
        <SharePanel sharingAllowed={sharingAllowed} />
        <ShareList shares={shares} />
      </Section>

      <Section title="History" description="The steps that make up your record, newest first.">
        <HistoryList initial={history.items} nextBefore={history.nextBefore} />
      </Section>
    </div>
  );
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  const id = `section-${title.toLowerCase().replace(/\s+/g, "-")}`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id={id} className="text-display-sm text-ink">
          {title}
        </h2>
        {description ? <p className="text-sm text-body">{description}</p> : null}
      </div>
      {children}
    </section>
  );
}
