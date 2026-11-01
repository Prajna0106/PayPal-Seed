import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowRight, Check } from "lucide-react";
import Link from "next/link";
import { HERO_CHECKS } from "../content";
import { HeroSeedCard } from "./hero-seed-card";
import { RevealGroup, RevealItem } from "./motion";

export function Hero() {
  return (
    <section aria-labelledby="hero-title" className="relative isolate overflow-hidden">
      <GradientOrb tone="mint" blend="sky" className="-top-48 -left-40 -z-10 size-[38rem]" />
      <GradientOrb tone="peach" blend="rose" className="top-24 -right-48 -z-10 size-[34rem] opacity-60" />
      <GradientOrb tone="lavender" blend="sky" className="-bottom-56 left-1/3 -z-10 size-[30rem] opacity-50" drift={false} />

      <div className="mx-auto grid max-w-6xl items-center gap-12 px-6 pt-8 pb-14 sm:pt-10 lg:min-h-[calc(100dvh-4rem)] lg:grid-cols-[1.1fr_1fr] lg:gap-16 lg:py-8">
        <RevealGroup>
          <RevealItem>
            <p className="inline-flex items-center gap-2 rounded-full border border-hairline bg-surface/80 px-3 py-1.5 text-xs font-medium text-body backdrop-blur">
              <span aria-hidden="true" className="size-2 rounded-full bg-gradient-mint" />
              A new banking layer inside PayPal
            </p>
          </RevealItem>
          <RevealItem>
            <h1 id="hero-title" className="text-display-mega mt-5 text-balance text-ink">
              Fair credit for the people banks <em className="italic">overlook.</em>
            </h1>
          </RevealItem>
          <RevealItem>
            <p className="mt-6 max-w-xl text-lg leading-relaxed text-pretty text-body">
              Seed brings saving and 0% microloans to the underbanked, inside the PayPal app they already use. Save from
              $5 a month, unlock a loan with no credit score and no fees, and get help before a payment is ever missed.
            </p>
          </RevealItem>
          <RevealItem>
            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
              <Button asChild size="lg" className="w-full sm:w-auto">
                <Link href="/login">
                  Start with $5
                  <ArrowRight aria-hidden="true" />
                </Link>
              </Button>
              <Button asChild size="lg" variant="outline" className="w-full sm:w-auto">
                <a href="#inclusion">Who Seed is for</a>
              </Button>
            </div>
          </RevealItem>
          <RevealItem>
            <ul className="mt-7 flex flex-wrap gap-x-5 gap-y-2 text-sm text-body">
              {HERO_CHECKS.map((item) => (
                <li key={item} className="inline-flex items-center gap-1.5">
                  <Check aria-hidden="true" className="size-4 text-success" />
                  {item}
                </li>
              ))}
            </ul>
          </RevealItem>
          <RevealItem>
            <p className="mt-6 max-w-xl border-l-2 border-gradient-mint pl-4 text-sm leading-relaxed text-muted-ink">
              Over a billion adults worldwide have no bank account, and many more cannot borrow fairly. Seed starts with
              the one account they already have. The demo runs on the PayPal Sandbox: no sign-up, no real money.
            </p>
          </RevealItem>
        </RevealGroup>

        <HeroSeedCard />
      </div>
    </section>
  );
}
