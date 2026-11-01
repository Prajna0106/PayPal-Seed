import { Briefcase, Check, Sprout, Users, Wallet } from "lucide-react";
import { AUDIENCES, COMPARISON } from "../content";
import { Reveal, RevealGroup, RevealItem } from "./motion";
import { Section } from "./section";

const AUDIENCE_ICONS = { gig: Briefcase, new: Sprout, cash: Wallet, young: Users } as const;

/** The product's reason to exist: who it serves and why it is different from the lenders they meet today. */
export function Inclusion() {
  return (
    <Section
      id="inclusion"
      eyebrow="Financial inclusion"
      title="Built for people the credit system skipped."
      intro="Millions of people earn, save and pay on time, yet have no credit score, an irregular income, or no lender who will say yes. Seed starts from what they already do in PayPal, beginning with saving and microloans."
    >
      <RevealGroup as="ul" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-label="Who Seed is for">
        {AUDIENCES.map((item) => {
          const Icon = AUDIENCE_ICONS[item.icon];
          return (
            <RevealItem as="li" key={item.title} className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-5">
              <span className="inline-flex size-10 items-center justify-center rounded-full bg-surface-strong text-ink">
                <Icon aria-hidden="true" className="size-5" />
              </span>
              <h3 className="text-display-sm text-ink">{item.title}</h3>
              <p className="text-[15px] leading-relaxed text-body">{item.body}</p>
            </RevealItem>
          );
        })}
      </RevealGroup>

      <Reveal className="mt-14">
        <h3 className="text-display-sm text-ink">What changes when the lender is Seed</h3>
        <div className="mt-5 hidden grid-cols-[10rem_1fr_1fr] gap-6 px-5 text-eyebrow text-muted-ink sm:grid">
          <span />
          <span>A typical lender</span>
          <span>Seed</span>
        </div>
        <ul className="mt-3 grid gap-3">
          {COMPARISON.map((row) => (
            <li
              key={row.label}
              className="grid gap-3 rounded-xl border border-hairline bg-surface p-5 sm:grid-cols-[10rem_1fr_1fr] sm:items-center sm:gap-6"
            >
              <p className="text-sm font-medium text-ink">{row.label}</p>
              <div className="grid grid-cols-2 gap-4 sm:contents">
                <p className="text-[15px] leading-snug text-body">
                  <span className="mb-1 block text-xs text-muted-ink sm:hidden">A typical lender</span>
                  {row.typical}
                </p>
                <p className="flex items-start gap-2 text-[15px] leading-snug font-medium text-ink">
                  <Check aria-hidden="true" className="mt-0.5 hidden size-4 shrink-0 text-success sm:block" />
                  <span>
                    <span className="mb-1 block text-xs font-normal text-muted-ink sm:hidden">Seed</span>
                    {row.seed}
                  </span>
                </p>
              </div>
            </li>
          ))}
        </ul>
      </Reveal>
    </Section>
  );
}
