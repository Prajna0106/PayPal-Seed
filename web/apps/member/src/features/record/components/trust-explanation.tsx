import type { Locale } from "@paypal-seed/i18n";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { Languages, ScrollText, Sparkles } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { trustWords } from "../copy";
import { getTrustRecord } from "../server/trust";

/**
 * The explanation and certificate wording the trust agent wrote, with the backend's own figures beside it. Text is
 * labelled when a model wrote it; no number is taken from the text, and there is no score or risk level here.
 */
export async function TrustExplanation({ locale }: { locale: Locale }) {
  const trust = await getTrustRecord(locale);
  if (!trust) {
    return null;
  }
  const t = trustWords(locale);
  const other: Locale = locale === "es" ? "en" : "es";
  const { figures } = trust;

  return (
    <section aria-labelledby="trust-title" lang={locale} className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 id="trust-title" className="text-display-sm text-ink">
            {t.title}
          </h2>
          <p className="max-w-2xl text-sm text-body">{t.description}</p>
        </div>
        <Link
          href={(other === "es" ? "/app/record?lang=es" : "/app/record") as Route}
          hrefLang={other}
          lang={other}
          className="inline-flex h-11 items-center gap-2 rounded-full border border-hairline-strong px-4 text-sm font-medium text-ink transition-colors hover:bg-surface-strong"
        >
          <Languages className="size-4" aria-hidden="true" />
          {t.language}
        </Link>
      </div>

      <div className="flex flex-col gap-5 rounded-3xl border border-hairline bg-canvas-soft p-6 sm:p-8">
        <h3 className="text-eyebrow text-muted-ink">{t.explanationHeading}</h3>
        <p className="max-w-2xl font-display text-2xl leading-snug font-light text-ink text-pretty">{trust.explanation}</p>
        <div className="flex flex-col gap-1 border-t border-hairline pt-4">
          <h3 className="text-eyebrow text-muted-ink">{t.adviceHeading}</h3>
          <p className="max-w-2xl text-sm text-body text-pretty">{trust.advice}</p>
        </div>
        {trust.aiWritten ? (
          <p className="inline-flex items-center gap-2 text-xs text-muted-ink">
            <Sparkles className="size-3.5" aria-hidden="true" />
            {t.aiLabel}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-3" role="group" aria-label={t.figuresLabel}>
        <h3 className="text-sm font-medium text-ink">{t.figuresLabel}</h3>
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
          <Kpi label={t.onTime} value={figures.onTimeRepayments} />
          <Kpi label={t.streak} value={figures.streakMonths} />
          <Kpi label={t.saved} value={formatMoney(figures.totalSavedCents)} />
          <Kpi label={t.repaid} value={formatMoney(figures.totalRepaidCents)} />
          <Kpi label={t.limitNow} value={formatMoney(figures.currentLimitCents)} />
          {figures.nextLimitCents === null ? null : <Kpi label={t.limitNext} value={formatMoney(figures.nextLimitCents)} />}
        </div>
      </div>

      <figure className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-5">
        <figcaption className="flex items-center gap-2 text-sm font-medium text-ink">
          <ScrollText className="size-4 text-muted-ink" aria-hidden="true" />
          {t.attestationHeading}
        </figcaption>
        <blockquote lang="en" className="text-sm text-body text-pretty">
          {trust.attestation}
        </blockquote>
        <p className="text-xs text-muted-ink">{t.attestationHint}</p>
        {trust.aiWritten ? (
          <p className="inline-flex items-center gap-2 text-xs text-muted-ink">
            <Sparkles className="size-3.5" aria-hidden="true" />
            {t.aiLabel}
          </p>
        ) : null}
        <dl className="flex flex-col gap-1 border-t border-hairline pt-3 text-xs text-muted-ink">
          <div className="flex flex-wrap gap-x-2">
            <dt>{t.issued}</dt>
            <dd className="tabular-nums">{formatBusinessDate(trust.issuedOn, "long", locale)}</dd>
          </div>
          <div className="flex flex-wrap gap-x-2">
            <dt>{t.verification}</dt>
            <dd className="font-mono break-all">{trust.verificationHash}</dd>
          </div>
        </dl>
      </figure>
    </section>
  );
}

export function TrustExplanationSkeleton({ label }: { label: string }) {
  return (
    <div className="flex flex-col gap-6" aria-busy="true" aria-label={label}>
      <div className="flex flex-col gap-2">
        <Skeleton className="h-7 w-1/2" />
        <Skeleton className="h-4 w-2/3" />
      </div>
      <Skeleton className="h-48 rounded-3xl" />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
        {Array.from({ length: 6 }, (_, i) => (
          <Skeleton key={i} className="h-28 rounded-xl" />
        ))}
      </div>
      <Skeleton className="h-40 rounded-2xl" />
    </div>
  );
}
