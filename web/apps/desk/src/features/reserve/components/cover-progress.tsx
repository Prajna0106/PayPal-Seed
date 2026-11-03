import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Check, Clock, Hourglass } from "lucide-react";
import type { CoverProgress as Progress } from "../model";

/**
 * Who has signed a cover request and when the decision is due. Plain props only, so a client component (the decision
 * dialog) can render it too. A breach is a badge with words, not colour alone.
 */
export function CoverProgress({ progress, className }: { progress: Progress; className?: string }) {
  return (
    <div className={className}>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
        {progress.signatures.map((line) => (
          <div key={line.role} className="flex flex-col gap-0.5">
            <dt className="text-xs text-muted-ink">{line.roleLabel}</dt>
            <dd className="flex flex-wrap items-center gap-1.5 text-ink">
              {line.at ? <Check aria-hidden="true" className="size-3.5 text-success" /> : <Hourglass aria-hidden="true" className="size-3.5 text-muted-ink" />}
              <span className={line.at ? undefined : "text-muted-ink"}>{line.who}</span>
              {line.at ? <span className="text-xs text-muted-ink">{line.at}</span> : null}
            </dd>
          </div>
        ))}
      </dl>
      {progress.next || progress.dueLabel ? (
        <p className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-ink [&_svg]:size-3.5">
          {progress.next ? <span>{progress.next}</span> : null}
          {progress.dueLabel ? (
            <span className="inline-flex items-center gap-1">
              <Clock aria-hidden="true" />
              Decision due {progress.dueLabel}
            </span>
          ) : null}
          {progress.breached ? <StatusPill tone="danger">Past its decision deadline</StatusPill> : null}
        </p>
      ) : null}
    </div>
  );
}
