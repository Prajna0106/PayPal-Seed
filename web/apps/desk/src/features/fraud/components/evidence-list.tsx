import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { evidenceFigures, FACT_KINDS, SIGNALS, signalName } from "../model";
import type { FlagEvidence } from "../server/queries";

/**
 * The evidence pack: every signal with its rule, weight and the facts it was computed from. Each rule can be redone
 * by hand from what is on screen, which is what an officer needs to answer a member's dispute.
 */
export function EvidenceList({ evidence }: { evidence: FlagEvidence }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Evidence</CardTitle>
        <CardDescription>
          Total weight {evidence.score}; a flag opens at {evidence.flagThreshold}. Rules {evidence.policyVersion}, generated{" "}
          {formatDateTime(evidence.generatedAt)}. Facts name no one: a tag only shows that two facts share a reference.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {evidence.signals.length === 0 ? (
          <p className="text-sm text-muted-ink">No signals are recorded for this flag.</p>
        ) : (
          <ol className="flex flex-col divide-y divide-hairline-soft">
            {evidence.signals.map(({ signal, facts }, index) => {
              const figures = evidenceFigures(signal.evidence as Record<string, unknown>);
              return (
                <li key={`${signal.code}-${index}`} className="flex flex-col gap-4 py-5 first:pt-0 last:pb-0">
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex min-w-0 flex-col gap-0.5">
                      <p className="font-medium text-ink">{signalName(signal.code)}</p>
                      <p className="font-mono text-xs text-muted-ink">{signal.code}</p>
                      <p className="mt-1 text-sm text-body">{signal.explanation}</p>
                      {SIGNALS[signal.code] ? <p className="text-xs text-muted-ink">Rule: {SIGNALS[signal.code]?.what}</p> : null}
                    </div>
                    <span
                      className="inline-flex h-7 min-w-12 shrink-0 items-center justify-center rounded-full bg-ink px-2.5 text-sm font-medium text-white tabular-nums"
                      aria-label={`Weight ${signal.weight}`}
                    >
                      +{signal.weight}
                    </span>
                  </div>

                  {figures.length > 0 ? (
                    <dl className="grid grid-cols-1 gap-x-6 gap-y-2 rounded-xl bg-canvas-soft p-4 sm:grid-cols-2 lg:grid-cols-3">
                      {figures.map((figure) => (
                        <div key={figure.key} className="flex min-w-0 flex-col gap-0.5">
                          <dt className="text-xs text-muted-ink">{figure.label}</dt>
                          <dd className="text-sm font-medium break-words text-ink tabular-nums">{figure.value}</dd>
                        </div>
                      ))}
                    </dl>
                  ) : null}

                  <div className="flex flex-col gap-2">
                    <p className="text-xs font-medium text-muted-ink">
                      Facts behind this signal · detected {formatDateTime(signal.detectedAt)}
                    </p>
                    {facts.length === 0 ? (
                      <p className="text-sm text-muted-ink">No individual facts are stored for this signal.</p>
                    ) : (
                      <ul className="flex flex-col divide-y divide-hairline-soft rounded-xl border border-hairline">
                        {facts.map((fact) => (
                          <li key={fact.factId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 px-4 py-3 text-sm">
                            <span className="flex flex-col">
                              <span className="font-medium text-ink">{FACT_KINDS[fact.kind] ?? fact.kind}</span>
                              <span className="text-xs text-muted-ink">
                                {formatDateTime(fact.occurredAt)} · fact <span className="font-mono">#{fact.factId}</span>
                              </span>
                            </span>
                            <span className="flex flex-wrap items-center gap-2 text-xs text-muted-ink">
                              {typeof fact.amountCents === "number" ? (
                                <span className="text-sm font-medium text-ink tabular-nums">{formatMoney(fact.amountCents)}</span>
                              ) : null}
                              {fact.referenceTag ? (
                                <span className="rounded-md bg-surface-strong px-1.5 py-0.5 font-mono text-ink-soft">tag {fact.referenceTag}</span>
                              ) : null}
                              {Object.entries(fact.attributes ?? {}).map(([name, value]) => (
                                <span key={name}>
                                  {name}: <span className="text-ink">{value}</span>
                                </span>
                              ))}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}
