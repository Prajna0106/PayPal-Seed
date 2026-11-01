import { StatusPill, type StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { cn } from "@paypal-seed/ui/lib/utils";
import { ArrowUpRight } from "lucide-react";
import { DESK_URL } from "../config";
import { EVIDENCE, QUEUE_ROWS } from "../content";
import { Section } from "./section";

const STATUS_TONE: Record<string, StatusTone> = {
  "Waiting for checker": "warning",
  "Waiting for maker": "info",
  Applied: "success",
  "Offer sent": "neutral",
};

/** A static mock of the officer desk: the queue (AG Grid in the product) and the evidence panel. */
export function OfficerGlimpse() {
  return (
    <Section
      id="officers"
      eyebrow="For loan officers"
      title="Evidence first, then a decision two people sign."
      intro="Officers see why a case opened, the numbers behind every signal, the options the code built and what the member chose. Fast decisions without evidence get flagged."
    >
      <div className="grid gap-5 lg:grid-cols-[1.35fr_1fr]">
        <figure className="overflow-hidden rounded-2xl border border-hairline bg-surface shadow-soft">
          <div className="flex items-center justify-between border-b border-hairline px-5 py-3.5">
            <p className="text-sm font-medium text-ink">Hardship queue</p>
            <span className="text-xs text-muted-ink">4 open cases</span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[34rem] text-left text-sm">
              <caption className="sr-only">Example hardship queue on the officer desk</caption>
              <thead className="bg-canvas-soft text-xs text-muted-ink">
                <tr>
                  <th scope="col" className="px-5 py-2.5 font-medium">Member</th>
                  <th scope="col" className="px-3 py-2.5 font-medium">Level</th>
                  <th scope="col" className="px-3 py-2.5 text-right font-medium">Points</th>
                  <th scope="col" className="px-3 py-2.5 font-medium">Choice</th>
                  <th scope="col" className="px-3 py-2.5 font-medium">Status</th>
                  <th scope="col" className="px-5 py-2.5 text-right font-medium">SLA</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline-soft">
                {QUEUE_ROWS.map((row, index) => (
                  <tr key={row.member} className={cn(index === 0 && "bg-canvas-soft/70")}>
                    <th scope="row" className="px-5 py-3 font-medium text-ink">{row.member}</th>
                    <td className="px-3 py-3 text-body">{row.level}</td>
                    <td className="px-3 py-3 text-right text-ink tabular-nums">{row.points}</td>
                    <td className="px-3 py-3 text-ink">{row.option}</td>
                    <td className="px-3 py-3">
                      <StatusPill tone={STATUS_TONE[row.status] ?? "neutral"}>{row.status}</StatusPill>
                    </td>
                    <td className="px-5 py-3 text-right text-body tabular-nums">{row.sla}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <figcaption className="sr-only">The officer desk queue, built with AG Grid in the product.</figcaption>
        </figure>

        <figure className="rounded-2xl border border-hairline bg-surface p-5 shadow-soft">
          <div className="flex items-center justify-between">
            <p className="text-sm font-medium text-ink">Evidence · Ana R.</p>
            <StatusPill tone="warning">Likely to miss · 7 points</StatusPill>
          </div>
          <ul className="mt-4 space-y-3">
            {EVIDENCE.map((signal) => (
              <li key={signal.code} className="rounded-xl border border-hairline-soft bg-canvas-soft p-3.5">
                <p className="flex items-baseline justify-between gap-3">
                  <span className="text-[15px] font-medium text-ink">{signal.name}</span>
                  <span className="text-sm text-ink tabular-nums">+{signal.points}</span>
                </p>
                <p className="mt-0.5 font-mono text-[11px] tracking-wide text-muted-ink">{signal.code}</p>
                <p className="mt-2 text-sm text-body">{signal.facts.join(" · ")}</p>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex items-center gap-2 border-t border-hairline-soft pt-4 text-sm text-body">
            <span aria-hidden="true" className="size-1.5 rounded-full bg-success" />
            Maker approved · waiting for a second, different officer
          </div>
          <figcaption className="sr-only">The evidence panel for one case, with each signal and its numbers.</figcaption>
        </figure>
      </div>

      <p className="mt-8">
        <a
          href={DESK_URL}
          className="inline-flex items-center gap-1.5 text-[15px] font-medium text-ink underline decoration-hairline-strong underline-offset-4 hover:decoration-ink"
        >
          Open the officer desk
          <ArrowUpRight aria-hidden="true" className="size-4" />
        </a>
      </p>
    </Section>
  );
}
