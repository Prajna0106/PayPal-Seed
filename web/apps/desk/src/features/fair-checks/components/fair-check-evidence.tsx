import { AgentCard } from "@paypal-seed/ui/brand/agent";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { formatBusinessDate, formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { CircleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { ASSESSMENT_STATUSES, type FairCheckCard, monthStart } from "../model";
import { CapacityChart } from "./capacity-chart";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-ink">{title}</h3>
      {children}
    </section>
  );
}

function Figure({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-muted-ink">{label}</dt>
      <dd className="text-sm font-medium text-ink tabular-nums">{children}</dd>
    </div>
  );
}

function List({ items }: { items: string[] }) {
  return (
    <ul className="flex list-disc flex-col gap-1 pl-5 text-sm text-body">
      {items.map((item) => (
        <li key={item}>{item}</li>
      ))}
    </ul>
  );
}

/**
 * The evidence behind one fair check, for an officer. Every figure is a structured field of the check, shown as
 * given. The sentences are model-written only when `modelGenerated` is true; otherwise they are fixed templates.
 */
export function FairCheckEvidence({ card }: { card: FairCheckCard }) {
  const decision = ASSESSMENT_STATUSES[card.officerDecision];
  const saved = card.monthsSaved ?? 0;
  const windowMonths = card.targetMonthsWindow ?? 0;

  const wordingBlocks = [
    { title: "Why, in the words the member sees", items: card.plainLanguageReasons },
    { title: "Questions to ask", items: card.officerQuestions ?? [] },
  ].filter((block) => block.items.length > 0);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center gap-2">
        <StatusPill tone={decision.tone}>{decision.label}</StatusPill>
        <StatusPill tone={card.recommended ? "success" : "warning"} dot={false}>
          {card.recommended ? "Recommended" : "Not recommended"}
        </StatusPill>
        {card.memberDisputed ? (
          <StatusPill tone="warning" dot={false}>
            Member disputes a number
          </StatusPill>
        ) : null}
        {card.highIncomeVolatility ? (
          <StatusPill tone="neutral" dot={false}>
            Income varies a lot
          </StatusPill>
        ) : null}
      </div>

      <AgentCard
        agent="underwriting"
        state={card.modelGenerated ? "ai" : "template"}
        footer={
          card.modelGenerated
            ? "Figures come from the calculation and were checked against this wording."
            : undefined
        }
      >
        <p className="text-sm text-body">
          Every figure comes from the calculation (model {card.modelVersion}, inputs {(card.inputsHash ?? "").slice(0, 8)}).{" "}
          {card.modelGenerated
            ? "The wording is the Underwriting Agent's: it only restates those figures."
            : "The sentences are fixed templates over those figures."}{" "}
          None of it is a credit score.
        </p>
      </AgentCard>

      {card.memberDisputed ? (
        <div role="note" className="flex items-start gap-2 rounded-xl border border-warning/30 bg-warning-soft p-3 text-sm text-warning [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0">
          <CircleAlert aria-hidden="true" />
          <div className="flex flex-col gap-1">
            <p className="font-medium">The member says a number is wrong</p>
            {card.memberDisputeNote ? <p className="text-pretty">{card.memberDisputeNote}</p> : null}
          </div>
        </div>
      ) : null}

      <Section title="Sizing">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
          <Figure label="Requested">
            {formatMoney(card.requestedAmountCents)} / {card.requestedTermMonths} mo
          </Figure>
          <Figure label="Suggested">
            {formatMoney(card.suggestedLoanCents)} / {card.suggestedTermMonths} mo
          </Figure>
          <Figure label="Suggested instalment">{formatMoney(card.suggestedMonthlyInstalmentCents)}</Figure>
          {card.instalmentCapCents !== undefined ? (
            <Figure label="Instalment cap">{formatMoney(card.instalmentCapCents)}</Figure>
          ) : null}
          {card.unlockedTier ? <Figure label="Tier">{card.unlockedTier}</Figure> : null}
          <Figure label="Purpose">{card.purpose}</Figure>
        </dl>
      </Section>

      <Section title="Income and capacity">
        <CapacityChart card={card} />
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
          {card.averageMonthlyIncomeCents !== undefined ? (
            <Figure label="Average monthly income">{formatMoney(card.averageMonthlyIncomeCents)}</Figure>
          ) : null}
          {card.lowestMonthlyIncomeCents !== undefined ? (
            <Figure label="Lowest month">
              {formatMoney(card.lowestMonthlyIncomeCents)}
              {card.lowestIncomeMonth ? (
                <span className="font-normal text-muted-ink"> ({formatBusinessDate(monthStart(card.lowestIncomeMonth), "month")})</span>
              ) : null}
            </Figure>
          ) : null}
          {card.coefficientOfVariation !== undefined ? (
            <Figure label="Income variability">{card.coefficientOfVariation.toFixed(2)}</Figure>
          ) : null}
        </dl>
        {card.usedFallbackStatedIncome ? (
          <p className="text-xs text-muted-ink">
            No consented income was available, so the member&apos;s stated figures were used.
          </p>
        ) : null}
      </Section>

      <Section title="Saving consistency">
        <div className="flex flex-col gap-2">
          <p className="text-sm text-ink tabular-nums">
            Saved in {saved} of the last {windowMonths} months
            {card.provenMonthlySavingCents !== undefined ? ` · ${formatMoney(card.provenMonthlySavingCents)} a month` : ""}
          </p>
          <div
            role="meter"
            aria-label="Months saved"
            aria-valuemin={0}
            aria-valuemax={windowMonths}
            aria-valuenow={Math.min(saved, windowMonths)}
            className="flex gap-1"
          >
            {Array.from({ length: windowMonths }, (_, index) => (
              <span
                key={index}
                aria-hidden="true"
                className={index < saved ? "h-2 flex-1 rounded-full bg-success" : "h-2 flex-1 rounded-full bg-surface-strong"}
              />
            ))}
          </div>
        </div>
      </Section>

      {wordingBlocks.length > 0 ? (
        <AgentCard
          agent="underwriting"
          state={card.modelGenerated ? "ai" : "template"}
          footer={card.modelGenerated ? "Figures come from the calculation and were checked against this wording." : undefined}
        >
          <div className="flex flex-col gap-5">
            {wordingBlocks.map((block) => (
              <Section key={block.title} title={block.title}>
                <List items={block.items} />
              </Section>
            ))}
          </div>
        </AgentCard>
      ) : null}

      <Section title="Data used">
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <p className="text-xs text-muted-ink">Read</p>
            <List items={card.usedData ?? []} />
          </div>
          <div className="flex flex-col gap-1.5">
            <p className="text-xs text-muted-ink">Never read</p>
            <List items={card.neverUsedData ?? []} />
          </div>
        </div>
      </Section>

      {card.officerDecision !== "PENDING" ? (
        <Section title="Decision">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-4 sm:grid-cols-3">
            <Figure label="Outcome">{decision.label}</Figure>
            {card.officerDecision !== "DECLINED" && card.decidedLoanCents !== undefined ? (
              <Figure label="Loan">
                {formatMoney(card.decidedLoanCents)} / {card.decidedTermMonths ?? 0} mo
              </Figure>
            ) : null}
            {card.decidedAt ? <Figure label="Decided">{formatDateTime(card.decidedAt)}</Figure> : null}
          </dl>
          {card.officerReason ? <p className="text-sm text-body text-pretty">&ldquo;{card.officerReason}&rdquo;</p> : null}
        </Section>
      ) : null}
    </div>
  );
}
