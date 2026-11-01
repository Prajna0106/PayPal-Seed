import { ChevronDown } from "lucide-react";
import { FAQS } from "../content";
import { Section } from "./section";

/** Native disclosure: keyboard and screen-reader friendly with no client JavaScript. */
export function Faq() {
  return (
    <Section id="faq" eyebrow="Questions" title="Straight answers." className="bg-canvas-soft">
      <div className="mx-auto max-w-3xl divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
        {FAQS.map((item) => (
          <details key={item.q} className="group px-6 [&_summary::-webkit-details-marker]:hidden">
            <summary className="flex min-h-14 cursor-pointer list-none items-center justify-between gap-4 py-4 text-[16px] font-medium text-ink">
              {item.q}
              <ChevronDown
                aria-hidden="true"
                className="size-4 shrink-0 text-muted-ink transition-transform group-open:rotate-180 motion-reduce:transition-none"
              />
            </summary>
            <p className="pb-5 text-[15px] leading-relaxed text-body">{item.a}</p>
          </details>
        ))}
      </div>
    </Section>
  );
}
