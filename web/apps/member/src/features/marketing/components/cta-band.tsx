import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { DESK_URL } from "../config";

export function CtaBand() {
  return (
    <section aria-labelledby="cta-title" className="px-6 py-20 sm:py-24">
      <div className="relative isolate mx-auto max-w-6xl overflow-hidden rounded-3xl bg-surface-dark px-6 py-16 text-center sm:px-12 sm:py-20">
        <GradientOrb tone="mint" blend="sky" className="-top-40 -left-24 -z-10 size-[28rem] opacity-40" />
        <GradientOrb tone="peach" blend="rose" className="-right-32 -bottom-48 -z-10 size-[30rem] opacity-35" />
        <p className="text-eyebrow text-on-dark-soft">Live demo</p>
        <h2 id="cta-title" className="text-display-xl mx-auto mt-4 max-w-2xl text-balance text-white">
          Walk through Ana&rsquo;s month, from the first signal to the new plan.
        </h2>
        <p className="mx-auto mt-5 max-w-xl text-base leading-relaxed text-on-dark-soft">
          Start as Ana in the member app, then switch to the officer desk and approve her choice with two different
          officers.
        </p>
        <div className="mt-9 flex flex-wrap items-center justify-center gap-3">
          <Button asChild size="lg" className="bg-white text-ink hover:bg-white/90">
            <Link href="/login">
              Start Ana&rsquo;s story
              <ArrowRight aria-hidden="true" />
            </Link>
          </Button>
          <Button
            asChild
            size="lg"
            variant="outline"
            className="border-white/30 text-white hover:border-white/60 hover:bg-white/10"
          >
            <a href={DESK_URL}>Open the officer desk</a>
          </Button>
        </div>
      </div>
    </section>
  );
}
