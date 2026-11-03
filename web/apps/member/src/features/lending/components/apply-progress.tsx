import { Progress } from "@paypal-seed/ui/components/progress";
import { Check } from "lucide-react";
import { STEPS, type StepId } from "../model";

/** Where the member is in the flow: a bar on small screens, named steps from `sm` up. */
export function ApplyProgress({ current }: { current: StepId }) {
  const index = STEPS.findIndex((step) => step.id === current);
  return (
    <nav aria-label="Progress" className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-4 text-sm">
        <p className="font-medium text-ink">
          Step {index + 1} of {STEPS.length}
        </p>
        <p className="text-muted-ink sm:hidden">{STEPS[index]?.label}</p>
      </div>
      <Progress value={((index + 1) / STEPS.length) * 100} aria-label={`Step ${index + 1} of ${STEPS.length}`} className="h-1.5 sm:hidden" />
      <ol className="hidden grid-cols-5 gap-2 sm:grid">
        {STEPS.map((step, i) => {
          const done = i < index;
          const here = i === index;
          return (
            <li key={step.id} aria-current={here ? "step" : undefined} className="flex flex-col gap-2">
              <span
                aria-hidden="true"
                className={`h-1 rounded-full ${done || here ? "bg-ink" : "bg-hairline-strong"}`}
              />
              <span className={`flex items-center gap-1.5 text-sm ${here ? "font-medium text-ink" : done ? "text-body" : "text-muted-ink"}`}>
                {done ? <Check aria-hidden="true" className="size-3.5" /> : null}
                {step.label}
                {done ? <span className="sr-only"> (done)</span> : null}
              </span>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
