import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Info } from "lucide-react";
import { BRANCHES, FLOW_STEPS } from "../content";
import { Reveal } from "./motion";
import { OfferGlimpse } from "./offer-glimpse";
import { Section } from "./section";

/** The safety net as an ordered list plus the A–D branches: real markup, readable by screen readers in order. */
export function SafetyNetFlow() {
  return (
    <div className="relative isolate overflow-hidden bg-canvas-soft">
      <GradientOrb tone="sky" blend="lavender" className="top-1/3 -right-64 -z-10 size-[36rem] opacity-50" />
      <Section
        id="safety-net"
        eyebrow="How the safety net works"
        title="Help arrives before the payment is missed, not after."
        intro="Every day Seed looks at loans with a payment coming up. When the numbers say a month is going to be hard, the member hears from us first, with options that are already worked out."
      >
        <ol className="grid gap-3 sm:grid-cols-2 lg:grid-cols-7">
          {FLOW_STEPS.map((step, index) => (
            <li
              key={step.title}
              className="relative flex flex-col gap-2 rounded-xl border border-hairline bg-surface p-4 lg:min-h-48"
            >
              <span className="text-xs font-medium text-muted-ink tabular-nums">
                {String(index + 1).padStart(2, "0")}
              </span>
              <h3 className="text-[15px] leading-snug font-medium text-ink">{step.title}</h3>
              <p className="text-sm leading-relaxed text-body">{step.body}</p>
              {index < FLOW_STEPS.length - 1 ? (
                <span
                  aria-hidden="true"
                  className="absolute top-1/2 -right-2.5 hidden size-2 -translate-y-1/2 rotate-45 border-t border-r border-hairline-strong lg:block"
                />
              ) : null}
            </li>
          ))}
        </ol>

        <div className="mt-12 grid gap-10 lg:grid-cols-2 lg:items-center lg:gap-16">
          <div>
            <h3 className="text-display-sm text-ink">Then the member chooses, and the right people approve.</h3>
            <p className="mt-3 text-[15px] leading-relaxed text-body">
              Small changes are approved by policy at once. Anything that reshapes the plan needs two different
              officers, and the database refuses a second signature from the same person.
            </p>
            <ul className="mt-6 grid gap-3 sm:grid-cols-2" aria-label="The four options and who approves them">
              {BRANCHES.map((branch) => (
                <li key={branch.label} className="flex items-start gap-3 rounded-xl border border-hairline bg-surface p-4">
                  <span
                    aria-hidden="true"
                    className="inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-ink text-sm font-medium text-white"
                  >
                    {branch.label}
                  </span>
                  <span>
                    <span className="block text-[15px] font-medium text-ink">
                      <span className="sr-only">Option {branch.label}: </span>
                      {branch.title}
                    </span>
                    <span className="mt-0.5 block text-sm text-body">{branch.outcome}</span>
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <Reveal>
            <OfferGlimpse className="mx-auto w-full max-w-md lg:max-w-none" />
          </Reveal>
        </div>

        <p className="mt-8 flex items-start gap-3 rounded-xl border border-gradient-peach/70 bg-warning-soft/60 px-5 py-4 text-[15px] text-ink">
          <Info aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-warning" />
          <span>
            <strong className="font-medium">The AI is optional.</strong> With no model key, or with the kill switch on,
            checked templates go out and every step above still works.
          </span>
        </p>
      </Section>
    </div>
  );
}
