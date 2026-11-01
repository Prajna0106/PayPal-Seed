import { ShieldCheck } from "lucide-react";
import { GUARDRAILS } from "../content";
import { Section } from "./section";

const PRINCIPLES = [
  { lead: "Numbers from code.", body: "Every amount, date and option is computed and tested in Java." },
  { lead: "Words from AI.", body: "The Guardian writes the message and the officer brief, nothing else." },
  { lead: "Decisions from people.", body: "The member chooses. Officers approve. The record stays honest." },
] as const;

export function Guardrails() {
  return (
    <Section
      id="trust"
      eyebrow="Trust and guardrails"
      title="An AI that can explain, but never decide."
      intro="Money advice has to be right every time. So the agent works inside walls that code enforces, not walls a prompt asks it to respect."
    >
      <div className="grid gap-px overflow-hidden rounded-2xl border border-hairline bg-hairline md:grid-cols-3">
        {PRINCIPLES.map((principle) => (
          <div key={principle.lead} className="bg-surface p-7">
            <p className="text-display-sm text-ink">{principle.lead}</p>
            <p className="mt-2 text-[15px] leading-relaxed text-body">{principle.body}</p>
          </div>
        ))}
      </div>

      <ul className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {GUARDRAILS.map((rail) => (
          <li key={rail.title} className="rounded-2xl border border-hairline bg-surface p-6 transition-shadow hover:shadow-soft">
            <ShieldCheck aria-hidden="true" className="size-5 text-ink" />
            <h3 className="mt-4 text-[17px] font-medium text-ink">{rail.title}</h3>
            <p className="mt-2 text-[15px] leading-relaxed text-body">{rail.body}</p>
          </li>
        ))}
      </ul>
    </Section>
  );
}
