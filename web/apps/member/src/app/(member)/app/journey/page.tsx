import { BusinessDate } from "@paypal-seed/ui/brand/business-date";
import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft, Route as RouteIcon } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { JourneyChart } from "@/features/savings/components/journey-chart";
import { Section } from "@/features/savings/components/section";
import { getJourney } from "@/features/savings/server/journey";

export const metadata: Metadata = { title: "Your journey" };

export default async function JourneyPage() {
  const { payload, today, reasons, loanOpen } = await getJourney();
  const steps = [...payload.events].sort((a, b) => a.startDate.localeCompare(b.startDate));

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 min-h-11 self-start">
        <Link href={"/app/savings" as Route}>
          <ArrowLeft aria-hidden="true" />
          Your goals
        </Link>
      </Button>

      <PageHeader
        eyebrow="Your journey"
        title="From saving to a loan"
        description="Your goals, the day a loan opens for you, and the payments of any loan you take, all on one line."
      />

      {steps.length === 0 ? (
        <EmptyState
          icon={<RouteIcon aria-hidden="true" />}
          title="Your journey starts with a first goal"
          description="Once you start saving, each step shows up here, from your first goal to a loan."
          action={
            <Button asChild size="lg">
              <Link href={"/app/savings/new" as Route}>Start a goal</Link>
            </Button>
          }
        />
      ) : (
        <>
          <JourneyChart payload={payload} today={today} />

          {!loanOpen && reasons.length > 0 ? (
            <Section id="next" title="What opens a loan" description="Steady saving is what opens the door.">
              <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-body">
                {reasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            </Section>
          ) : null}

          <Section id="steps" title="Step by step">
            <ol className="flex flex-col divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
              {steps.map((step) => (
                <li key={step.id} className="flex flex-col gap-1 p-4 sm:flex-row sm:items-baseline sm:gap-6">
                  <BusinessDate value={step.startDate} dateStyle="long" className="w-44 shrink-0 text-sm text-muted-ink" />
                  <p className="text-sm text-body">
                    <span className="font-medium text-ink">{step.name}</span>. {step.detail}
                  </p>
                </li>
              ))}
            </ol>
          </Section>
        </>
      )}
    </div>
  );
}
