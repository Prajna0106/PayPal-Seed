import { SeedMark } from "@paypal-seed/ui/brand/seed-mark";
import { cn } from "@paypal-seed/ui/lib/utils";
import { LockOpen } from "lucide-react";
import { HERO_CARD } from "../content";
import { GrowBar } from "./motion";

/**
 * The hero visual: three months of small saving unlocking a first 0% loan. Drawn in markup so it stays sharp and
 * readable; the figures are an example, not computed. It carries no sentences of its own: the figure is the story.
 */
export function HeroSeedCard({ className }: { className?: string }) {
  return (
    <figure className={cn("relative mx-auto w-full max-w-md lg:max-w-none", className)}>
      <div
        aria-hidden="true"
        className="absolute inset-x-8 -bottom-3 h-full rounded-2xl border border-hairline bg-surface/70 shadow-soft"
      />
      <div className="relative overflow-hidden rounded-2xl border border-hairline bg-surface shadow-lift transition-transform duration-500 ease-out hover:-translate-y-1 hover:-rotate-1 motion-reduce:transition-none motion-reduce:hover:transform-none">
        <div className="flex items-center justify-between px-7 pt-6">
          <p className="flex items-center gap-2 text-sm font-medium text-ink">
            <SeedMark className="size-5" />
            Seed savings
          </p>
          <span className="flex items-center gap-2 text-xs text-muted-ink">
            <span aria-hidden="true" className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-gradient-mint opacity-75 motion-reduce:animate-none" />
              <span className="relative inline-flex size-2 rounded-full bg-success" />
            </span>
            Example
          </span>
        </div>

        <div className="space-y-7 px-7 pt-7 pb-7">
          <div>
            <p className="text-display-md text-ink tabular-nums">
              {HERO_CARD.saved} <span className="font-sans text-sm text-body">saved</span>
            </p>
            <p className="mt-1 text-sm text-body">{HERO_CARD.cadence}</p>
            <ul aria-label="Three months of saving" className="mt-4 grid grid-cols-3 gap-2">
              {HERO_CARD.months.map((month, index) => (
                <li key={month} aria-label={month}>
                  <div className="h-1.5 overflow-hidden rounded-full bg-surface-strong">
                    <GrowBar
                      delay={0.5 + index * 0.25}
                      className="h-full rounded-full bg-linear-to-r from-gradient-mint to-gradient-sky"
                    />
                  </div>
                </li>
              ))}
            </ul>
          </div>

          <div className="rounded-xl bg-canvas-soft p-6">
            <p className="flex items-center gap-2 text-eyebrow text-success">
              <LockOpen aria-hidden="true" className="size-3.5" />
              Loan unlocked
            </p>
            <p className="text-display-md mt-2 text-ink tabular-nums">
              {HERO_CARD.loan} <span className="font-sans text-sm text-body">at 0% interest</span>
            </p>
            <p className="mt-1 text-sm text-body">{HERO_CARD.terms}</p>
          </div>
        </div>
      </div>

      <figcaption className="sr-only">
        An example of Seed in the PayPal app: $25 saved each month for three months unlocks a $300 loan at 0% interest,
        sized from your own PayPal activity with your consent. No credit score is used.
      </figcaption>
    </figure>
  );
}
