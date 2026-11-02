import { Eye, EyeOff, Hourglass, Undo2 } from "lucide-react";
import type { ReactNode } from "react";
import type { IncomeConsent } from "../model";
import { ConsentChoice, type ChoiceFields } from "./consent-choice";

/** Step 3: the plain-words consent. What is read, for how long, that it can end any time, and that "no" is fine. */
export function ConsentStep({ consent, fields }: { consent: IncomeConsent | null; fields: ChoiceFields }) {
  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="fair-check" className="flex flex-col gap-3">
        <h2 id="fair-check" className="text-display-sm text-ink">
          May we read your PayPal income?
        </h2>
        <p className="text-body text-pretty">
          Seed makes a fair check so your payments fit your real month. Reading your PayPal income lets us do that without you having to type
          or prove anything. It is your choice, and saying no will not stop you applying.
        </p>
      </section>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel icon={<Eye aria-hidden="true" />} title="What we read" items={consent?.usedData} fallback="The money that came into your PayPal account over about the last three months." />
        <Panel icon={<EyeOff aria-hidden="true" />} title="What we never read" items={consent?.neverUsedData} fallback="What you buy, who you pay, or your messages." />
      </div>

      <ul className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6 text-body">
        <li className="flex items-start gap-3">
          <Hourglass aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-ink" />
          <span>We look at about the last three months, once for each fair check, and keep the weekly totals only while you agree.</span>
        </li>
        <li className="flex items-start gap-3">
          <Undo2 aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-ink" />
          <span>
            You can take your OK back at any time in Settings. We stop reading straight away and delete the income we already read.
          </span>
        </li>
      </ul>

      <ConsentChoice fields={fields} alreadyAllowed={consent?.consented ?? false} />
    </div>
  );
}

function Panel({ icon, title, items, fallback }: { icon: ReactNode; title: string; items: string[] | undefined; fallback: string }) {
  const list = items && items.length > 0 ? items : [fallback];
  return (
    <section className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
      <h3 className="flex items-center gap-2 text-title-sm font-medium text-ink [&_svg]:size-4 [&_svg]:text-muted-ink">
        {icon}
        {title}
      </h3>
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-body text-pretty">
        {list.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </section>
  );
}
