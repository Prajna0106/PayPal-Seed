import { Card, CardContent, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { cn } from "@paypal-seed/ui/lib/utils";
import { Circle, CircleCheck, CircleDot, CircleX } from "lucide-react";
import type { TimelineStep } from "../model";

const MARKS = {
  done: { icon: CircleCheck, className: "text-success", text: "Done" },
  current: { icon: CircleDot, className: "text-ink", text: "Next" },
  todo: { icon: Circle, className: "text-muted-ink", text: "Not yet" },
  stopped: { icon: CircleX, className: "text-danger", text: "Stopped" },
} as const;

/** Where the application is on its way to a paid loan. The state is spoken as well as drawn, not colour alone. */
export function StatusTimeline({ steps }: { steps: TimelineStep[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Progress</CardTitle>
      </CardHeader>
      <CardContent>
        <ol className="flex flex-col gap-4" aria-label="Application progress">
          {steps.map((step) => {
            const mark = MARKS[step.state];
            const Icon = mark.icon;
            return (
              <li key={step.key} className="flex items-start gap-3">
                <span className={cn("mt-0.5 [&_svg]:size-5", mark.className)}>
                  <Icon aria-hidden="true" />
                </span>
                <div className="flex flex-col">
                  <p className={cn("text-sm", step.state === "todo" ? "text-muted-ink" : "font-medium text-ink")}>
                    {step.label}
                    <span className="sr-only"> ({mark.text})</span>
                  </p>
                  {step.detail ? <p className="text-xs text-muted-ink">{step.detail}</p> : null}
                </div>
              </li>
            );
          })}
        </ol>
      </CardContent>
    </Card>
  );
}
