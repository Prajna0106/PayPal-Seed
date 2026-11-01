import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowRight } from "lucide-react";
import Link from "next/link";
import { OfferGlimpse } from "./offer-glimpse";

export function Hero() {
  return (
    <section aria-labelledby="hero-title" className="relative isolate overflow-hidden">
      <GradientOrb tone="mint" blend="sky" className="-top-48 -left-40 -z-10 size-[38rem]" />
      <GradientOrb tone="peach" blend="rose" className="top-24 -right-48 -z-10 size-[34rem] opacity-60" />
      <GradientOrb tone="lavender" blend="sky" className="-bottom-56 left-1/3 -z-10 size-[30rem] opacity-50" drift={false} />

      <div className="mx-auto grid max-w-6xl items-center gap-10 px-6 pt-6 pb-14 sm:pt-8 lg:min-h-[calc(100dvh-4rem)] lg:grid-cols-[1.1fr_1fr] lg:gap-16 lg:py-8">
        <div className="motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-2 motion-safe:duration-700">
          <p className="text-eyebrow text-muted-ink">Savings and fair microloans, inside PayPal</p>
          <h1 id="hero-title" className="text-display-mega mt-5 text-balance text-ink">
            Save a little. Borrow fairly. Get help before a month goes wrong.
          </h1>
          <p className="mt-6 max-w-xl text-lg leading-relaxed text-pretty text-body">
            Seed turns steady saving into a 0% loan with no fees, then watches out for you. When income dips before a
            due date, it offers a few clear ways through the month. You choose, and a person approves.
          </p>
          <div className="mt-9 flex flex-wrap items-center gap-3">
            <Button asChild size="lg">
              <Link href="/login">
                Open the demo
                <ArrowRight aria-hidden="true" />
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline">
              <a href="#safety-net">See how the safety net works</a>
            </Button>
          </div>
          <p className="mt-6 text-sm text-muted-ink">Runs on the PayPal Sandbox. No sign-up, no real money.</p>
        </div>

        <OfferGlimpse className="mx-auto w-full max-w-md motion-safe:animate-in motion-safe:fade-in motion-safe:duration-1000 lg:max-w-none" />
      </div>
    </section>
  );
}
