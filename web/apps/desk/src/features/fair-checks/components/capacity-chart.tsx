"use client";

import { type ChartConfig, ChartContainer } from "@paypal-seed/ui/components/chart";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Bar, BarChart, Cell, LabelList, XAxis, YAxis } from "recharts";
import type { FairCheckCard } from "../model";

const config = { cents: { label: "Per month" } } satisfies ChartConfig;

interface BarDatum {
  key: string;
  label: string;
  cents: number;
  fill: string;
}

/** The monthly figures behind the sizing, drawn on one scale. Every bar is a field of the check, none is computed. */
function barsFor(card: FairCheckCard): BarDatum[] {
  const bars: (BarDatum | null)[] = [
    card.averageMonthlyIncomeCents === undefined
      ? null
      : { key: "average", label: "Average income", cents: card.averageMonthlyIncomeCents, fill: "var(--color-ink)" },
    card.lowestMonthlyIncomeCents === undefined
      ? null
      : { key: "lowest", label: "Lowest month", cents: card.lowestMonthlyIncomeCents, fill: "var(--color-ink)" },
    card.instalmentCapCents === undefined
      ? null
      : { key: "cap", label: "Instalment cap", cents: card.instalmentCapCents, fill: "var(--color-chart-4)" },
    {
      key: "suggested",
      label: "Suggested instalment",
      cents: card.suggestedMonthlyInstalmentCents,
      fill: "var(--color-chart-4)",
    },
    card.provenMonthlySavingCents === undefined
      ? null
      : { key: "saving", label: "Saved each month", cents: card.provenMonthlySavingCents, fill: "var(--color-success)" },
  ];
  return bars.filter((bar): bar is BarDatum => bar !== null);
}

export function CapacityChart({ card }: { card: FairCheckCard }) {
  const data = barsFor(card);
  return (
    <figure className="flex flex-col gap-2">
      <ChartContainer
        config={config}
        className="aspect-auto w-full"
        style={{ height: data.length * 40 + 8 }}
        aria-label="Monthly income, instalment cap, suggested instalment and saving"
      >
        <BarChart data={data} layout="vertical" margin={{ left: 4, right: 72 }} accessibilityLayer>
          <XAxis type="number" hide domain={[0, "dataMax"]} />
          <YAxis type="category" dataKey="label" width={132} tickLine={false} axisLine={false} />
          <Bar dataKey="cents" radius={6} barSize={16}>
            {data.map((bar) => (
              <Cell key={bar.key} fill={bar.fill} />
            ))}
            <LabelList dataKey="cents" position="right" formatter={(value: unknown) => formatMoney(Number(value))} className="fill-ink text-xs" />
          </Bar>
        </BarChart>
      </ChartContainer>
      <figcaption className="text-xs text-muted-ink">
        Monthly amounts from the calculation. Income is the member&apos;s consented income, or what they stated.
      </figcaption>
    </figure>
  );
}
