import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight, Bot, MessageCircleHeart } from "lucide-react";
import Link from "next/link";
import { applyHref, type FairCheckCard, type WizardParams } from "../model";
import { DisputeForm } from "./dispute-form";

/**
 * Step 4. Every figure is a structured field from the backend. The sentences are shown as given and labelled, and no
 * score, tier or internal signal appears.
 */
export function CheckResult({ card, params }: { card: FairCheckCard; params: WizardParams }) {
  const lower = card.recommended && card.suggestedLoanCents < card.requestedAmountCents;
  return (
    <div className="flex flex-col gap-8">
      {card.recommended ? (
        <section aria-labelledby="suggestion" className="flex flex-col gap-4">
          <h2 id="suggestion" className="text-display-sm text-ink">
            Here is what we suggest
          </h2>
          <div className="grid gap-4 sm:grid-cols-3">
            <Kpi label="Safe amount for you" value={formatMoney(card.suggestedLoanCents)} />
            <Kpi label="Over" value={card.suggestedTermMonths} hint="Months, in equal payments." />
            <Kpi label="Each month" value={formatMoney(card.suggestedMonthlyInstalmentCents)} hint="0% interest, no fees." />
          </div>
          {lower ? (
            <p className="text-body text-pretty">
              You asked for {formatMoney(card.requestedAmountCents)}. We suggest a little less so that every payment stays comfortable.
              You are in charge: you can go back and choose differently.
            </p>
          ) : null}
        </section>
      ) : (
        <section aria-labelledby="not-now" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
          <MessageCircleHeart aria-hidden="true" className="size-6 text-muted-ink" />
          <h2 id="not-now" className="text-display-sm text-ink">
            We cannot suggest this loan just yet
          </h2>
          <p className="text-body text-pretty">
            That is about the numbers in front of us today, not about you. A smaller amount or a longer term may work, and you can ask a
            person to look again if something below is wrong.
          </p>
        </section>
      )}

      <section aria-labelledby="why-this" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <h2 id="why-this" className="text-title-sm font-medium text-ink">
          Why
        </h2>
        {card.plainLanguageReasons.length > 0 ? (
          <ul className="flex list-disc flex-col gap-1.5 pl-5 text-body text-pretty">
            {card.plainLanguageReasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        ) : null}
        <p className="flex items-start gap-2 text-xs text-muted-ink">
          <Bot aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span>
            Explained in plain words, and some of the wording may be written by Seed&apos;s assistant. Every figure on this page comes from
            our own calculations, never from the assistant.
          </span>
        </p>
      </section>

      <section aria-labelledby="how" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <h2 id="how" className="text-title-sm font-medium text-ink">
          What we looked at
        </h2>
        <p className="text-body text-pretty">
          {card.usedFallbackStatedIncome
            ? "We used the income you told us."
            : "We read your PayPal income, with your OK."}
          {card.averageMonthlyIncomeCents !== undefined && card.averageMonthlyIncomeCents > 0
            ? ` That is about ${formatMoney(card.averageMonthlyIncomeCents)} a month.`
            : ""}
          {card.instalmentCapCents !== undefined && card.instalmentCapCents > 0
            ? ` A comfortable monthly payment for you is up to ${formatMoney(card.instalmentCapCents)}.`
            : ""}
        </p>
        <div className="grid gap-4 text-sm sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <h3 className="font-medium text-ink">We used</h3>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body">
              {card.usedData.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
          <div className="flex flex-col gap-1.5">
            <h3 className="font-medium text-ink">We never use</h3>
            <ul className="flex list-disc flex-col gap-1 pl-5 text-body">
              {card.neverUsedData.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          </div>
        </div>
      </section>

      {card.memberDisputed ? (
        <p role="status" className="rounded-lg bg-info-soft px-4 py-3 text-sm text-info">
          Thank you for telling us. A person at Seed will look at this. Nothing changes until they have.
        </p>
      ) : (
        <DisputeForm assessmentId={card.assessmentId} />
      )}

      <div className="flex flex-col gap-3 sm:flex-row">
        {card.recommended ? (
          <Button asChild size="lg" className="h-12">
            <Link href={applyHref("review", params)}>
              Review and apply
              <ArrowRight aria-hidden="true" />
            </Link>
          </Button>
        ) : null}
        <Button asChild size="lg" variant="outline" className="h-12">
          <Link href={applyHref("amount", params)}>Choose a different amount</Link>
        </Button>
      </div>
    </div>
  );
}
