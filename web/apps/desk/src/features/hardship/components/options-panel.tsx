import type { HardshipCaseDetail } from "@paypal-seed/api-client/types";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { cn } from "@paypal-seed/ui/lib/utils";
import { Sparkles } from "lucide-react";
import { OPTION_NAMES, optionHeadline, summaryFigures, TIER_NAMES, WARNING_NAMES } from "../model";

type Option = NonNullable<HardshipCaseDetail["options"]>[number];

/** The options the member was offered, as the engine previewed them, with the member's choice highlighted. */
export function OptionsPanel({ options, chosen, revision }: { options: Option[]; chosen?: string | null; revision?: number | null }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Options offered</CardTitle>
        <CardDescription>
          {revision ? `Offer revision ${revision}. ` : null}Every figure is the engine&apos;s preview; the suggested option was
          picked by code, not by the Guardian.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {options.length === 0 ? (
          <p className="text-sm text-muted-ink">No offer was built for this case.</p>
        ) : (
          <ol className="grid gap-3 md:grid-cols-2">
            {options.map((option) => {
              const label = option.label ?? "?";
              const isChosen = chosen === label;
              const summary = option.summary as Record<string, unknown> | undefined;
              return (
                <li
                  key={label}
                  className={cn(
                    "flex flex-col gap-3 rounded-xl border bg-surface p-4",
                    isChosen ? "border-ink ring-1 ring-ink" : "border-hairline",
                  )}
                >
                  <div className="flex items-start gap-3">
                    <span
                      className={cn(
                        "inline-flex size-8 shrink-0 items-center justify-center rounded-full text-sm font-medium",
                        isChosen ? "bg-ink text-white" : "bg-surface-strong text-ink",
                      )}
                    >
                      {label}
                    </span>
                    <div className="flex min-w-0 flex-col gap-1">
                      <p className="font-medium text-ink">{OPTION_NAMES[label] ?? option.primitive}</p>
                      <p className="text-sm text-body">{optionHeadline(option.primitive ?? "", summary)}</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {isChosen ? <StatusPill tone="ink">Member chose this</StatusPill> : null}
                    {option.recommended ? (
                      <StatusPill tone="info" dot={false}>
                        <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
                          <Sparkles aria-hidden="true" />
                          Suggested
                        </span>
                      </StatusPill>
                    ) : null}
                    {option.sustainable === true ? <StatusPill tone="success">Sustainable</StatusPill> : null}
                    {(option.warnings ?? []).map((warning) => (
                      <StatusPill key={warning} tone="warning">
                        {WARNING_NAMES[warning] ?? warning}
                      </StatusPill>
                    ))}
                    <StatusPill tone="neutral" dot={false}>
                      {TIER_NAMES[option.tier ?? ""] ?? option.tier}
                    </StatusPill>
                  </div>
                  <dl className="grid grid-cols-2 gap-x-4 gap-y-1.5 border-t border-hairline-soft pt-3 text-sm">
                    {summaryFigures(summary).map((figure) => (
                      <div key={figure.key} className="flex justify-between gap-2">
                        <dt className="text-muted-ink">{figure.label}</dt>
                        <dd className="font-medium text-ink tabular-nums">{figure.value}</dd>
                      </div>
                    ))}
                  </dl>
                </li>
              );
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}
