import { AgentBadge } from "@paypal-seed/ui/brand/agent";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowUpRight } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { FairCheckCard } from "../model";

const DECISION = {
  PENDING: { label: "Awaiting an officer", tone: "warning" },
  APPROVED: { label: "Officer approved", tone: "success" },
  ADJUSTED: { label: "Officer adjusted", tone: "info" },
  DECLINED: { label: "Officer declined", tone: "danger" },
} as const;

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs text-muted-ink">{label}</dt>
      <dd className="text-sm font-medium text-ink tabular-nums">{value}</dd>
    </div>
  );
}

/** The fair check beside the application: the headline figures, and a link to the full evidence and the decision. */
export function FairCheckSummary({ card }: { card: FairCheckCard | null }) {
  if (!card) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Fair check</CardTitle>
          <CardDescription>No fair check is linked to this application yet.</CardDescription>
        </CardHeader>
      </Card>
    );
  }
  const decision = DECISION[card.officerDecision];
  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>Fair check</CardTitle>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <AgentBadge agent="underwriting" state={card.modelGenerated ? "ai" : "template"} />
            <StatusPill tone={decision.tone}>{decision.label}</StatusPill>
          </div>
        </div>
        <CardDescription>
          {card.recommended ? "The calculation recommends a loan." : "The calculation does not recommend this loan as asked."}
          {card.memberDisputed ? " The member says a number is wrong." : ""}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <dl className="grid grid-cols-2 gap-x-6 gap-y-4">
          <Row label="Suggested loan" value={formatMoney(card.suggestedLoanCents)} />
          <Row label="Suggested term" value={`${card.suggestedTermMonths} months`} />
          <Row label="Suggested instalment" value={formatMoney(card.suggestedMonthlyInstalmentCents)} />
          {card.averageMonthlyIncomeCents !== undefined ? (
            <Row label="Average monthly income" value={formatMoney(card.averageMonthlyIncomeCents)} />
          ) : null}
          <Row label="Months saved" value={`${card.monthsSaved ?? 0} of ${card.targetMonthsWindow ?? 0}`} />
        </dl>
        {card.usedFallbackStatedIncome ? (
          <p className="text-xs text-muted-ink">Income is as the member stated it: no consented income was available.</p>
        ) : null}
        <Button asChild variant="outline" className="h-11 self-start">
          <Link href={`/fair-checks/${card.assessmentId}` as Route}>
            Open the evidence
            <ArrowUpRight aria-hidden="true" />
          </Link>
        </Button>
      </CardContent>
    </Card>
  );
}
