import type { HardshipCaseDetail } from "@paypal-seed/api-client/types";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { evidenceFigures, SIGNAL_NAMES } from "../model";

type Signal = NonNullable<HardshipCaseDetail["signals"]>[number];

/** What the scorecard saw: each fired signal with its points and the exact numbers behind it. */
export function EvidencePanel({ signals, points, policyVersion }: { signals: Signal[]; points: number; policyVersion?: string }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Evidence</CardTitle>
        <CardDescription>
          {points} point{points === 1 ? "" : "s"} from the scorecard
          {policyVersion ? <span className="text-muted-ink"> · policy {policyVersion}</span> : null}. Every number below
          comes from the member&apos;s own consented data.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {signals.length === 0 ? (
          <p className="text-sm text-muted-ink">No signals fired. The member asked for help directly.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-hairline-soft">
            {signals.map((signal) => {
              const figures = evidenceFigures(signal.evidence as Record<string, unknown> | undefined);
              return (
                <li key={signal.code} className="flex flex-col gap-3 py-4 first:pt-0 last:pb-0">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex flex-col">
                      <p className="font-medium text-ink">{SIGNAL_NAMES[signal.code ?? ""] ?? signal.code}</p>
                      <p className="font-mono text-xs text-muted-ink">{signal.code}</p>
                    </div>
                    <span className="inline-flex h-7 min-w-12 items-center justify-center rounded-full bg-ink px-2.5 text-sm font-medium text-white tabular-nums">
                      +{signal.points ?? 0}
                    </span>
                  </div>
                  {figures.length > 0 ? (
                    <dl className="grid grid-cols-2 gap-x-6 gap-y-2 rounded-xl bg-canvas-soft p-4 sm:grid-cols-4">
                      {figures.map((figure) => (
                        <div key={figure.key} className="flex flex-col gap-0.5">
                          <dt className="text-xs text-muted-ink">{figure.label}</dt>
                          <dd className="text-sm font-medium text-ink tabular-nums">{figure.value}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
