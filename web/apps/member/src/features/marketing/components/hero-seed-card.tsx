import { SeedMark } from "@paypal-seed/ui/brand/seed-mark";
import { cn } from "@paypal-seed/ui/lib/utils";
import { LockOpen, ShieldCheck } from "lucide-react";
import { HERO_CARD } from "../content";
import { Float, GrowBar } from "./motion";

/**
 * The hero visual: three months of small saving unlocking a first 0% loan. Drawn in markup so it stays sharp and
 * readable; the figures are an example, not computed.
 */
export function HeroSeedCard({ className }: { className?: string }) {
  return (
    <figure className={cn("relative mx-auto w-full max-w-md lg:max-w-none", className)}>
      <div
        aria-hidden="true"
        className="absolute inset-x-6 -bottom-3 h-full rounded-2xl border border-hairline bg-surface/70 shadow-soft"
      />
      <div className="relative overflow-hidden rounded-2xl border border-hairline bg-surface shadow-lift">
        <div className="flex items-center justify-between border-b border-hairline-soft px-5 py-3.5">
          <p className="flex items-center gap-2 text-sm font-medium text-ink">
            <SeedMark className="size-5" />
            Seed savings
          </p>
          <span className="rounded-full bg-surface-strong px-2.5 py-1 text-xs text-muted-ink">Example</span>
        </div>

        <div className="space-y-5 px-5 py-5">
          <div>
            <p className="text-sm text-muted-ink">{HERO_CARD.goal}</p>
            <p className="text-display-md text-ink tabular-nums">
              {HERO_CARD.saved} <span className="font-sans text-sm text-body">saved</span>
            </p>
            <p className="mt-1 text-sm text-body">{HERO_CARD.cadence}</p>
          </div>

          <ul aria-label="Three months of saving" className="grid grid-cols-3 gap-2">
            {HERO_CARD.months.map((month, index) => (
              <li key={month}>
                <div className="h-1.5 overflow-hidden rounded-full bg-surface-strong">
                  <GrowBar
                    delay={0.5 + index * 0.25}
                    className="h-full rounded-full bg-linear-to-r from-gradient-mint to-gradient-sky"
                  />
                </div>
                <p className="mt-1.5 text-xs text-muted-ink">{month}</p>
              </li>
            ))}
          </ul>

          <div className="rounded-xl border border-ink/25 bg-canvas-soft p-4">
            <p className="flex items-center gap-2 text-eyebrow text-success">
              <LockOpen aria-hidden="true" className="size-3.5" />
              Loan unlocked
            </p>
            <p className="text-display-md mt-2 text-ink tabular-nums">
              {HERO_CARD.loan} <span className="font-sans text-sm text-body">at 0% interest</span>
            </p>
            <p className="mt-1 text-sm text-body">{HERO_CARD.terms}</p>
          </div>

          <p className="flex items-start gap-2 border-t border-hairline-soft pt-4 text-xs text-muted-ink">
            <ShieldCheck aria-hidden="true" className="mt-px size-4 shrink-0" />
            Sized from your own PayPal activity, with your consent. No credit score is used.
          </p>
        </div>
      </div>

      <Float className="absolute -top-4 right-4 hidden sm:block">
        <span className="inline-flex items-center gap-2 rounded-full border border-hairline bg-surface px-3 py-1.5 text-xs font-medium text-ink shadow-soft">
          <span aria-hidden="true" className="size-2 rounded-full bg-gradient-mint" />
          No credit score needed
        </span>
      </Float>
      <figcaption className="sr-only">
        An example of Seed in the PayPal app: $25 saved each month for three months unlocks a $300 loan at 0% interest.
      </figcaption>
    </figure>
  );
}
