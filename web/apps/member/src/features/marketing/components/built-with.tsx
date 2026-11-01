import { BUILT_WITH } from "../content";
import { Section } from "./section";

export function BuiltWith() {
  return (
    <Section
      id="built-with"
      eyebrow="Built with"
      title="PayPal rails underneath, good tools around them."
      className="border-t border-hairline"
    >
      <div className="grid gap-10 md:grid-cols-[12rem_1fr]">
        {BUILT_WITH.map((group) => (
          <div key={group.group} className="contents">
            <h3 className="text-sm font-medium text-muted-ink">{group.group}</h3>
            <ul className="grid grid-cols-2 gap-px overflow-hidden rounded-xl border border-hairline bg-hairline sm:grid-cols-3 lg:grid-cols-4">
              {group.items.map((item) => (
                <li key={item} className="bg-surface px-5 py-4 font-display text-lg font-light text-ink">
                  {item}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </Section>
  );
}
