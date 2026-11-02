import { cn } from "@paypal-seed/ui/lib/utils";
import { ScrollText } from "lucide-react";
import { LEVELS, levelLabel, plural } from "../copy";
import type { CreditRecordSection } from "../model";
import { SectionCard } from "./section-card";

/** The member's Seed record level in words, five quiet steps, and what the next one needs. Never a score. */
export function RecordCard({ record }: { record: CreditRecordSection }) {
  const step = LEVELS.indexOf(record.level);
  const next = record.nextStep;
  const toGo = [
    next?.onTimeSavingMonthsToGo ? `${plural(next.onTimeSavingMonthsToGo, "saving month", "saving months")} to go` : null,
    next?.loansRepaidOnPlanToGo ? `${plural(next.loansRepaidOnPlanToGo, "loan", "loans")} repaid on plan to go` : null,
  ].filter((part): part is string => part !== null);

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
          {toGo.length > 0 ? <p className="text-xs text-muted-ink">{toGo.join(" · ")}</p> : null}
        </>
      ) : (
        <p className="text-sm text-body">You are at the top level. Thank you for saving with Seed.</p>
      )}
    </SectionCard>
  );
}
