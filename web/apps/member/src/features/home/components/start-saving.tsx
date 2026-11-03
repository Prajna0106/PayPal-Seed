import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { Sprout } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";

/** The first thing a brand-new member sees: one small, pressure-free step. */
export function StartSaving() {
  return (
    <section
      aria-labelledby="start-saving"
      className="relative isolate flex flex-col gap-5 overflow-hidden rounded-3xl border border-hairline bg-canvas-soft p-8 sm:p-10"
    >
      <GradientOrb tone="mint" blend="lavender" className="-top-24 -right-16 -z-10 size-72 opacity-60" />
      <Sprout aria-hidden="true" className="size-6 text-muted-ink" />
      <div className="flex max-w-xl flex-col gap-2">
        <h2 id="start-saving" className="text-display-md text-ink text-balance">
          Start with $5 a month
        </h2>
        <p className="text-body text-pretty">
          Pick something to save for. Seed puts a little aside each month, and saving steadily is how you unlock a fair 0% loan. You can pause or stop at any time.
        </p>
      </div>
      <Button asChild size="lg" className="self-start">
        <Link href={"/app/savings/new" as Route}>Start saving</Link>
      </Button>
    </section>
  );
}
