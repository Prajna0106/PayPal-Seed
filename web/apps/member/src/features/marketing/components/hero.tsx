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

      <div className="mx-auto grid max-w-6xl items-center gap-14 px-6 pt-10 pb-16 lg:min-h-[calc(100dvh-4rem)] lg:grid-cols-[1.1fr_1fr] lg:gap-20 lg:py-10">
        <RevealGroup>
          <RevealItem>
            <h1 id="hero-title" className="text-display-mega text-balance text-ink">
              Fair credit for the people banks <em className="italic">overlook.</em>
            </h1>
          </RevealItem>
          <RevealItem>
            <p className="mt-6 max-w-md text-xl leading-snug text-pretty text-body">
              Save from $5 a month. Unlock a 0% loan, inside PayPal.
            </p>
          </RevealItem>
          <RevealItem>
            <Button asChild size="lg" className="mt-9 w-full sm:w-auto">
              <Link href="/login">
                Start with $5
                <ArrowRight aria-hidden="true" />
              </Link>
            </Button>
          </RevealItem>
          <RevealItem>
            <ul className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-sm text-body">
              {HERO_CHECKS.map((item) => (
                <li key={item} className="inline-flex items-center gap-1.5">
                  <Check aria-hidden="true" className="size-4 text-success" />
                  {item}
                </li>
              ))}
            </ul>
          </RevealItem>
        </RevealGroup>

        <HeroSeedCard />
      </div>
    </section>
  );
}
