"use client";

import type { HardshipCaseStatus } from "@paypal-seed/api-client/types";
import type { StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { type ChartConfig, ChartContainer, ChartTooltip, ChartTooltipContent } from "@paypal-seed/ui/components/chart";
import { Bar, BarChart, Cell, XAxis, YAxis } from "recharts";

const TONE_FILL: Record<StatusTone, string> = {
  neutral: "var(--color-muted-soft)",
  info: "var(--color-chart-4)",
  warning: "var(--color-warning)",
  danger: "var(--color-danger)",
  success: "var(--color-success)",
  ink: "var(--color-ink)",
};

const config = { count: { label: "Cases" } } satisfies ChartConfig;

export interface StatusBar {
  status: HardshipCaseStatus;
  label: string;
  tone: StatusTone;
  count: number;
}

/** Recent cases by status, one bar per status, coloured by what the status means for the desk. */
export function StatusChart({ data }: { data: StatusBar[] }) {
  return (
    <ChartContainer config={config} className="aspect-auto h-64 w-full" aria-label="Cases by status">
      <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16 }} accessibilityLayer>
        <XAxis type="number" allowDecimals={false} hide />
        <YAxis type="category" dataKey="label" width={120} tickLine={false} axisLine={false} />
        <ChartTooltip cursor={{ fill: "var(--color-canvas-soft)" }} content={<ChartTooltipContent hideIndicator />} />
        <Bar dataKey="count" radius={6} barSize={18}>
          {data.map((bar) => (
            <Cell key={bar.status} fill={TONE_FILL[bar.tone]} />
          ))}
        </Bar>
      </BarChart>
    </ChartContainer>
  );
}
