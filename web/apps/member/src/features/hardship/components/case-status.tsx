import type { Locale } from "@paypal-seed/i18n";
import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { CheckCircle2, Clock } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { fill, money, words } from "../copy";

export interface CaseStatusProps {
  loanId: string;
  status: string;
  locale: Locale;
  /** From the choice response (carried in the URL): when the officers' decision is due. */
  decisionDueAt?: string | null;
  /** Option D: the amount to pay with PayPal. */
  payNowCents?: number | null;
}

/** What happens next after the member chose: reviewing, approved, applied, or "pay to finish". */
export function CaseStatus({ loanId, status, locale, decisionDueAt, payNowCents }: CaseStatusProps) {
  const t = words(locale);
  const done = status === "APPLIED" || status === "APPROVED";
  const message = (() => {
    switch (status) {
      case "PENDING_APPROVAL":
        return decisionDueAt ? fill(t.reviewing, { when: formatDateTime(decisionDueAt, { dateStyle: "medium", timeStyle: "short" }) }) : t.reviewingNoDate;
      case "APPROVED":
        return t.approved;
      case "APPLIED":
        return t.applied;
      case "APPROVED_EXECUTION_FAILED":
        return t.executionDelayed;
      case "OPTION_CHOSEN":
        return payNowCents ? fill(t.payToFinish, { amount: money(payNowCents) }) : t.reviewingNoDate;
      default:
        return t.reviewingNoDate;
    }
  })();
  const Icon = done ? CheckCircle2 : Clock;

  return (
    <section
      aria-live="polite"
      className="relative isolate flex flex-col gap-5 overflow-hidden rounded-3xl border border-hairline bg-canvas-soft p-6 sm:p-10"
    >
      <GradientOrb tone={done ? "mint" : "sky"} blend="lavender" className="-top-24 -right-16 -z-10 size-80 opacity-50" />
      <span className="flex size-11 items-center justify-center rounded-full bg-surface text-ink" aria-hidden="true">
        <Icon className="size-5" />
      </span>
      <p className="max-w-xl font-display text-3xl leading-tight font-light text-ink text-balance">{message}</p>
      <Button asChild variant="outline" className="self-start">
        <Link href={`/app/loans/${loanId}` as Route}>{t.viewPlan}</Link>
      </Button>
    </section>
  );
}
