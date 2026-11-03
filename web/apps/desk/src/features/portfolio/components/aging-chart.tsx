"use client";

import type { Schemas } from "@paypal-seed/api-client/types";
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from "@paypal-seed/ui/components/chart";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { Bar, BarChart, XAxis, YAxis } from "recharts";
import { AGING_BUCKETS } from "../model";

type Bucket = Schemas["PortfolioSummary"]["byAgingBucket"][number];

const config = Object.fromEntries(
  Object.entries(AGING_BUCKETS).map(([bucket, { label, fill }]) => [bucket, { label, color: fill }]),
) satisfies ChartConfig;

/**
 * Loans still owed, split by how late they are, as one stacked bar. The list below is the same data in words and
 * cents, so the chart is never the only way to read it.
 */
export function AgingChart({ buckets }: { buckets: Bucket[] }) {
  const row = Object.fromEntries(buckets.map((b) => [b.bucket, b.loans]));
  const owing = buckets.reduce((sum, b) => sum + b.loans, 0);
  return (
    <div className="flex flex-col gap-4">
      {owing === 0 ? (
        <p className="text-sm text-muted-ink">No loan is owing right now.</p>
      ) : (
        <ChartContainer config={config} className="aspect-auto h-16 w-full" aria-hidden="true">
          <BarChart data={[row]} layout="vertical" margin={{ left: 0, right: 0, top: 0, bottom: 0 }}>
            <XAxis type="number" hide domain={[0, owing]} />
            <YAxis type="category" hide />
            <ChartTooltip cursor={false} content={<ChartTooltipContent hideLabel />} />
            {buckets.map((b, index) => (
              <Bar
                key={b.bucket}
                dataKey={b.bucket}
                stackId="aging"
                fill={`var(--color-${b.bucket})`}
                radius={index === 0 ? [8, 0, 0, 8] : index === buckets.length - 1 ? [0, 8, 8, 0] : 0}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </ChartContainer>
      )}
      <ul className="grid gap-2 text-sm sm:grid-cols-2">
        {buckets.map((b) => {
          const meta = AGING_BUCKETS[b.bucket];
          return (
            <li key={b.bucket} className="flex items-center justify-between gap-3 rounded-lg border border-hairline-soft px-3 py-2">
              <span className="flex items-center gap-2 text-ink">
                <span aria-hidden="true" className="size-2.5 rounded-full" style={{ background: meta.fill }} />
                {meta.label}
              </span>
              <span className="text-body tabular-nums">
                {b.loans.toLocaleString("en-US")} · {formatMoney(b.outstandingCents)}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
