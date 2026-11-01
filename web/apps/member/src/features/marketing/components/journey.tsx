import { JOURNEY } from "../content";
import { GrowBar, RevealGroup, RevealItem } from "./motion";
import { Section } from "./section";

const TONE_BG = {
  mint: "bg-gradient-mint",
  peach: "bg-gradient-peach",
  lavender: "bg-gradient-lavender",
  sky: "bg-gradient-sky",
  rose: "bg-gradient-rose",
} as const;

export function Journey() {
  return (
    <Section
      id="journey"
      eyebrow="The member journey"
      title="From the first $5 to a bigger, fairer loan."
      intro="Seed lives inside the PayPal app people already use. Saving proves you can repay, so the loan never depends on a credit score."
    >
      <GrowBar
        className="mb-4 h-0.5 rounded-full bg-linear-to-r from-gradient-mint via-gradient-sky via-gradient-lavender via-gradient-peach to-gradient-rose"
      />
      <RevealGroup
        as="ol"
        className="grid gap-px overflow-hidden rounded-2xl border border-hairline bg-hairline sm:grid-cols-2 lg:grid-cols-5"
      >
        {JOURNEY.map((item, index) => (
          <RevealItem
            as="li"
            key={item.step}
            className={`relative flex flex-col gap-4 bg-surface p-6 ${index === JOURNEY.length - 1 ? "sm:col-span-2 lg:col-span-1" : ""}`}
          >
            <span className="flex items-center gap-3">
              <span aria-hidden="true" className={`size-2.5 rounded-full ${TONE_BG[item.tone]}`} />
              <span className="text-eyebrow text-muted-ink">Step {item.step}</span>
            </span>
            <h3 className="text-display-sm text-ink">{item.title}</h3>
            <p className="flex-1 text-[15px] leading-relaxed text-body">{item.body}</p>
            <p className="text-xs font-medium text-muted-ink">{item.detail}</p>
          </RevealItem>
        ))}
      </RevealGroup>
    </Section>
  );
}
