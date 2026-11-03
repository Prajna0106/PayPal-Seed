import { Check, Circle, CircleDot, Minus } from "lucide-react";
import { applicationTimeline } from "../status";
import type { ApplicationStatus } from "../model";

/** The path an application takes, as a list the member (and a screen reader) can follow. */
export function ApplicationTimeline({ status }: { status: ApplicationStatus }) {
  const steps = applicationTimeline(status);
  return (
    <ol aria-label="Where your application is" className="flex flex-col">
      {steps.map((step, index) => {
        const last = index === steps.length - 1;
        return (
          <li key={step.key} aria-current={step.state === "current" ? "step" : undefined} className="relative flex gap-4 pb-6 last:pb-0">
            {last ? null : (
              <span
                aria-hidden="true"
                className={`absolute top-8 left-[15px] h-[calc(100%-2rem)] w-px ${step.state === "done" ? "bg-ink" : "bg-hairline-strong"}`}
              />
            )}
            <span
              aria-hidden="true"
              className={`grid size-8 shrink-0 place-items-center rounded-full border ${
                step.state === "done"
                  ? "border-ink bg-ink text-white"
                  : step.state === "current"
                    ? "border-ink bg-surface text-ink"
                    : "border-hairline-strong bg-surface text-muted-ink"
              }`}
            >
              {step.state === "done" ? (
                <Check className="size-4" />
              ) : step.state === "current" ? (
                <CircleDot className="size-4" />
              ) : step.state === "stopped" ? (
                <Minus className="size-4" />
              ) : (
                <Circle className="size-3" />
              )}
            </span>
            <span className={`pt-1 text-[15px] ${step.state === "upcoming" ? "text-muted-ink" : "font-medium text-ink"}`}>
              {step.label}
              <span className="sr-only">
                {step.state === "done" ? " (done)" : step.state === "current" ? " (now)" : step.state === "upcoming" ? " (next)" : ""}
              </span>
            </span>
          </li>
        );
      })}
    </ol>
  );
}
