import type { Locale } from "@paypal-seed/i18n";
import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { CircleAlert, Sparkles, UserRoundCheck, Zap, Wallet } from "lucide-react";
import { randomUUID } from "node:crypto";
import { date, describeOption, fill, money, words } from "../copy";
import type { HardshipModel, OfferOption } from "../server/queries";
import { ChooseOption } from "./choose-option";
import { DeclineForm } from "./decline-form";

const TIER_ICON = { AUTO_POLICY: Zap, MAKER_CHECKER: UserRoundCheck, NONE: Wallet } as const;
const ORDER: Record<string, number> = { A: 0, B: 1, C: 2, D: 3 };

/**
 * The offer: the message, then the four options side by side, all equal, the suggested one marked but never
 * preselected, and a plain "No thanks". Every figure in the cards comes from the option summary.
 */
export function OfferView({ loanId, hardship, locale }: { loanId: string; hardship: HardshipModel; locale: Locale }) {
  const t = words(locale);
  const offer = hardship.offer;
  if (!offer) {
    return null;
  }
  // One key per rendered offer revision: a retry reuses it, a new revision (after a change) gets a new one.
  const attemptKey = randomUUID();
  const options = [...offer.options].sort((a, b) => (ORDER[a.label] ?? 9) - (ORDER[b.label] ?? 9));

  return (
    <div className="flex flex-col gap-10">
      {offer.message ? (
        <section aria-labelledby="offer-message" className="relative isolate overflow-hidden rounded-3xl border border-hairline bg-canvas-soft p-6 sm:p-8">
          <GradientOrb tone="peach" blend="lavender" className="-top-24 -left-16 -z-10 size-72 opacity-50" />
          <h2 id="offer-message" className="text-eyebrow text-muted-ink">
            {t.message}
          </h2>
          <p className="mt-3 max-w-2xl font-display text-2xl leading-snug font-light text-ink text-pretty">{offer.message.text}</p>
          {offer.message.source === "AI" ? (
            <p className="mt-4 inline-flex items-center gap-2 text-xs text-muted-ink">
              <Sparkles className="size-3.5" aria-hidden="true" />
              {t.aiLabel}
            </p>
          ) : null}
        </section>
      ) : null}

      <section aria-labelledby="offer-options" className="flex flex-col gap-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 id="offer-options" className="text-display-sm text-ink">
            {hardship.target
              ? fill(t.target, { amount: money(hardship.target.amountCents), date: date(hardship.target.dueDate, locale) })
              : t.title}
          </h2>
          <p className="text-sm text-muted-ink">
            {t.noFees} {t.youChoose}
          </p>
        </div>
        <ul className="grid gap-4 md:grid-cols-2">
          {options.map((option) => (
            <li key={option.optionId}>
              <OptionCard option={option} loanId={loanId} caseId={hardship.caseId} offerId={offer.offerId} attemptKey={attemptKey} locale={locale} />
            </li>
          ))}
        </ul>
      </section>

      <footer className="flex flex-col gap-3 border-t border-hairline pt-6 sm:flex-row sm:items-center sm:justify-between">
        <DeclineForm loanId={loanId} caseId={hardship.caseId} attemptKey={attemptKey} locale={locale} />
        {offer.expiresOn ? <p className="text-sm text-muted-ink">{fill(t.expires, { date: date(offer.expiresOn, locale) })}</p> : null}
      </footer>
    </div>
  );
}

function OptionCard({
  option,
  loanId,
  caseId,
  offerId,
  attemptKey,
  locale,
}: {
  option: OfferOption;
  loanId: string;
  caseId: string;
  offerId: string;
  attemptKey: string;
  locale: Locale;
}) {
  const t = words(locale);
  const title = t.titles[option.label];
  const description = describeOption(option.label, option.summary, locale);
  const TierIcon = TIER_ICON[option.tier];
  const headingId = `option-${option.label}`;

  return (
    <article
      aria-labelledby={headingId}
      data-recommended={option.recommended || undefined}
      className="relative flex h-full flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6 data-[recommended]:border-ink"
    >
      <header className="flex items-start justify-between gap-3">
        <div className="flex items-center gap-3">
          <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-strong font-display text-lg text-ink" aria-hidden="true">
            {option.label}
          </span>
          <h3 id={headingId} className="text-lg font-medium text-ink">
            {title}
          </h3>
        </div>
        {option.recommended ? (
          <span className="rounded-full bg-ink px-2.5 py-1 text-[11px] font-semibold tracking-wide text-white uppercase">
            {locale === "es" ? "Sugerida para ti" : "Suggested for you"}
          </span>
        ) : null}
      </header>

      <p className="text-body text-pretty">{description}</p>

      {option.warnings.length > 0 ? (
        <ul className="flex flex-col gap-1.5">
          {option.warnings.map((warning) => (
            <li key={warning} className="flex items-start gap-2 text-sm text-warning">
              <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              {t.warnings[warning] ?? warning}
            </li>
          ))}
        </ul>
      ) : null}

      <p className="mt-auto flex items-start gap-2 text-sm text-muted-ink">
        <TierIcon className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {t.tiers[option.tier]}
      </p>

      <ChooseOption
        loanId={loanId}
        caseId={caseId}
        offerId={offerId}
        optionId={option.optionId}
        attemptKey={attemptKey}
        title={title}
        description={description}
        recommended={option.recommended}
        locale={locale}
      />
    </article>
  );
}
