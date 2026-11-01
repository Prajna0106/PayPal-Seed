import { isLocale, type Locale, translator } from "@paypal-seed/i18n";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Alert, AlertDescription } from "@paypal-seed/ui/components/alert";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft, RefreshCw } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { AskHelpForm } from "@/features/hardship/components/ask-help-form";
import { CaseStatus } from "@/features/hardship/components/case-status";
import { LanguageToggle } from "@/features/hardship/components/language-toggle";
import { OfferView } from "@/features/hardship/components/offer-view";
import { date, fill, words } from "@/features/hardship/copy";
import { getHardshipPage } from "@/features/hardship/server/queries";
import { getPlan } from "@/features/loans/server/queries";

export const metadata: Metadata = { title: "Help with a payment" };

/** Statuses in which the member is still looking at an offer. */
const OPEN_OFFER = new Set(["DETECTED", "OUTREACH_SENT", "OFFER_VIEWED"]);
const KNOWN_STATUS = new Set(["OPTION_CHOSEN", "PENDING_APPROVAL", "APPROVED", "APPROVED_EXECUTION_FAILED", "APPLIED"]);

export default async function HelpPage({ params, searchParams }: PageProps<"/app/loans/[loanId]/help">) {
  const [{ loanId }, query] = await Promise.all([params, searchParams]);
  const locale: Locale = isLocale(query.lang) ? query.lang : "en";
  const t = words(locale);
  const [{ hardship, lastChange }, plan] = await Promise.all([getHardshipPage(loanId, locale), getPlan(loanId)]);

  const after = {
    status: typeof query.status === "string" && KNOWN_STATUS.has(query.status) ? query.status : null,
    due: typeof query.due === "string" && !Number.isNaN(Date.parse(query.due)) ? query.due : null,
    pay: typeof query.pay === "string" && /^\d{1,9}$/.test(query.pay) ? Number(query.pay) : null,
  };
  const changedNotice = query.changed === "1";
  const showOffer = hardship?.offer && OPEN_OFFER.has(hardship.status);
  const back = (
    <Button asChild variant="ghost" size="sm" className="-ml-3 self-start">
      <Link href={`/app/loans/${loanId}` as Route}>
        <ArrowLeft aria-hidden="true" />
        {locale === "es" ? "Tu plan" : "Your plan"}
      </Link>
    </Button>
  );

  return (
    <div lang={locale} className="flex flex-col gap-10">
      {back}
      <PageHeader
        eyebrow={locale === "es" ? "Red de seguridad" : "Safety net"}
        title={t.title}
        description={`${t.noFees} ${t.youChoose}`}
        actions={<LanguageToggle loanId={loanId} locale={locale} />}
      />

      {changedNotice && showOffer ? (
        <Alert role="status" className="border-warning/30 bg-warning-soft">
          <RefreshCw className="text-warning" aria-hidden="true" />
          <AlertDescription className="text-ink">{t.stale}</AlertDescription>
        </Alert>
      ) : null}

      {hardship && showOffer ? (
        <OfferView key={hardship.offer?.revision} loanId={loanId} hardship={hardship} locale={locale} />
      ) : hardship ? (
        <CaseStatus loanId={loanId} status={hardship.status} locale={locale} decisionDueAt={after.due} payNowCents={after.pay} />
      ) : after.status ? (
        <CaseStatus loanId={loanId} status={after.status} locale={locale} decisionDueAt={after.due} payNowCents={after.pay} />
      ) : (
        <NoOpenCase loanId={loanId} locale={locale} active={plan.loanStatus === "ACTIVE"} lastChange={lastChange} />
      )}
    </div>
  );
}

function NoOpenCase({
  loanId,
  locale,
  active,
  lastChange,
}: {
  loanId: string;
  locale: Locale;
  active: boolean;
  lastChange: { version: number; createdOn: string } | null;
}) {
  const t = translator(locale);
  const es = locale === "es";
  return (
    <div className="grid gap-8 lg:grid-cols-[1fr_1.2fr]">
      <div className="flex flex-col gap-3">
        <h2 className="text-display-sm text-ink">{t("hardship.askTitle")}</h2>
        <p className="text-body text-pretty">
          {es
            ? "Si un pago se ve difícil, avísanos antes de la fecha. Te mostraremos opciones sin cargos, y pedir ayuda nunca cuenta en tu contra."
            : "If a payment looks hard, tell us before it is due. We'll show you options with no fees, and asking for help never counts against you."}
        </p>
        {lastChange ? (
          <p className="text-sm text-muted-ink">
            {fill(es ? "Tu plan se actualizó por última vez el {date}." : "Your plan was last updated through the safety net on {date}.", {
              date: date(lastChange.createdOn, locale),
            })}
          </p>
        ) : null}
      </div>
      {active ? (
        <div className="rounded-2xl border border-hairline bg-surface p-6">
          <AskHelpForm loanId={loanId} locale={locale} />
        </div>
      ) : (
        <p className="text-body">{es ? "Este préstamo ya está pagado." : "This loan is already paid off."}</p>
      )}
    </div>
  );
}
