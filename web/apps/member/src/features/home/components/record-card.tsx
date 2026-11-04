import { cn } from "@paypal-seed/ui/lib/utils";
import { ScrollText } from "lucide-react";
import { LEVELS, levelLabel, plural } from "../copy";
import type { CreditRecordSection } from "../model";
import { DrawBar } from "./home-motion";
import { SectionCard } from "./section-card";

/** The member's Seed record level in words, five quiet steps, and what the next one needs. Never a score. */
export function RecordCard({ record }: { record: CreditRecordSection }) {
  const step = LEVELS.indexOf(record.level);
  const next = record.nextStep;
  const toGo = [
    next?.onTimeSavingMonthsToGo ? `${plural(next.onTimeSavingMonthsToGo, "saving month", "saving months")} to go` : null,
    next?.loansRepaidOnPlanToGo ? `${plural(next.loansRepaidOnPlanToGo, "loan", "loans")} repaid on plan to go` : null,
  ].filter((part): part is string => part !== null);

  // Both figures are the backend's: the months still to go and what the next level asks in all. Nothing is derived
  // beyond the difference the contract describes, and the line is left out when the backend sends no total.
  const required = next?.requiredOnTimeSavingMonths ?? null;
  const savingProgress =
    next && required !== null && required > 0
      ? (() => {
          const done = Math.min(required, Math.max(0, required - (next.onTimeSavingMonthsToGo ?? 0)));
          return { percent: (done / required) * 100, label: `${done} of ${plural(required, "month", "months")} saving on time` };
        })()
      : null;

  return (
    <SectionCard title="Your Seed record" icon={<ScrollText aria-hidden="true" />} href="/app/record" linkLabel="See your record">
      <div className="flex flex-col gap-3">
        <p className="font-display text-3xl font-light tracking-tight text-ink">{levelLabel(record.level)}</p>
        <ol className="flex gap-1.5" aria-label={`Level ${step + 1} of ${LEVELS.length}`}>
          {LEVELS.map((level, index) => (
            <li key={level} className={cn("h-1.5 flex-1 rounded-full", index <= step ? "bg-ink" : "bg-surface-strong")}>
              <span className="sr-only">{levelLabel(level)}</span>
            </li>
          ))}
        </ol>
      </div>

      {next ? (
        <>
          <p className="text-sm text-body text-pretty">{next.hint}</p>
          {savingProgress ? (
            <div className="flex flex-col gap-2">
              <DrawBar percent={savingProgress.percent} label={savingProgress.label} />
              <p className="text-xs text-muted-ink tabular-nums">{savingProgress.label}</p>
            </div>
          ) : null}
          {toGo.length > 0 ? <p className="text-xs text-muted-ink">{toGo.join(" · ")}</p> : null}
        </>
      ) : (
        <p className="text-sm text-body">You are at the top level. Thank you for saving with Seed.</p>
      )}
    </SectionCard>
  );
}
