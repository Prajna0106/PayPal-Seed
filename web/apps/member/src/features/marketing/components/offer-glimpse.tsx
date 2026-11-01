import { cn } from "@paypal-seed/ui/lib/utils";
import { OFFER } from "../content";

/**
 * A static picture of the member's offer, drawn with real markup so it stays sharp, accessible and translatable.
 * The figures are Ana's worked example; nothing here is computed.
 */
export function OfferGlimpse({ className }: { className?: string }) {
  return (
    <figure className={cn("relative", className)}>
      <div
        aria-hidden="true"
        className="absolute inset-x-6 -bottom-3 h-full rounded-2xl border border-hairline bg-surface/70 shadow-soft"
      />
      <div className="relative overflow-hidden rounded-2xl border border-hairline bg-surface shadow-lift">
        <div className="flex items-center justify-between border-b border-hairline-soft px-5 py-3.5">
          <p className="text-sm font-medium text-ink">A gentler way through November</p>
          <span className="rounded-full bg-surface-strong px-2.5 py-1 text-xs text-muted-ink">{OFFER.dueLabel}</span>
        </div>
        <div className="space-y-4 px-5 py-5">
          <p className="text-[15px] leading-relaxed text-body">{OFFER.message}</p>
          <ul className="grid gap-2.5" aria-label="Options for this payment">
            {OFFER.options.map((option) => (
              <li
                key={option.label}
                className={cn(
                  "flex items-start gap-3 rounded-xl border px-3.5 py-3",
                  option.suggested ? "border-ink/25 bg-canvas-soft" : "border-hairline",
                )}
              >
                <span
                  aria-hidden="true"
                  className={cn(
                    "mt-0.5 inline-flex size-7 shrink-0 items-center justify-center rounded-full text-sm font-medium",
                    option.suggested ? "bg-ink text-white" : "bg-surface-strong text-ink",
                  )}
                >
                  {option.label}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="text-[15px] font-medium text-ink">
                      <span className="sr-only">Option {option.label}: </span>
                      {option.title}
                    </span>
                    {option.suggested ? (
                      <span className="rounded-full bg-success-soft px-2 py-0.5 text-xs font-medium text-success">
                        Suggested for you
                      </span>
                    ) : null}
                  </span>
                  <span className="mt-0.5 block text-sm text-body">{option.line}</span>
                  <span className="mt-1 block text-xs text-muted-ink">{option.note}</span>
                </span>
              </li>
            ))}
          </ul>
          <div className="flex items-center justify-between gap-3 border-t border-hairline-soft pt-4">
            <span className="text-xs text-muted-ink">No fees on any option</span>
            <span className="text-sm font-medium text-ink underline decoration-hairline-strong underline-offset-4">
              No thanks
            </span>
          </div>
        </div>
      </div>
      <figcaption className="sr-only">
        An example offer in the member app: four ways to handle a $150 payment, with the smaller, longer plan suggested.
      </figcaption>
    </figure>
  );
}
