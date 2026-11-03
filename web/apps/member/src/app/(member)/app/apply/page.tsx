import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { ArrowLeft } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { AmountForm } from "@/features/lending/components/amount-form";
import { ApplyProgress } from "@/features/lending/components/apply-progress";
import { CheckResult } from "@/features/lending/components/check-result";
import { ConsentStep } from "@/features/lending/components/consent-step";
import { EligibilityStep } from "@/features/lending/components/eligibility-step";
import { ReviewStep } from "@/features/lending/components/review-step";
import { applyHref, type StepId } from "@/features/lending/model";
import { getApplyContext, getAssessment, getReviewContext } from "@/features/lending/server/queries";
import { clampedChoice, limitsOf, readWizard } from "@/features/lending/server/wizard";

export const metadata: Metadata = { title: "Borrow" };

const HEADINGS: Record<StepId, { title: string; description: string }> = {
  eligibility: {
    title: "Borrow with Seed",
    description: "Your saving has earned you a say. Here is what is open to you, and why.",
  },
  amount: {
    title: "Choose an amount",
    description: "Borrow only what you need. You can change your mind on any step.",
  },
  consent: {
    title: "Your OK",
    description: "One question, in plain words. Either answer lets you carry on.",
  },
  check: {
    title: "Your fair check",
    description: "A calm look at what fits your month.",
  },
  review: {
    title: "Review and apply",
    description: "Check everything once, then send it. A person at Seed reads every application.",
  },
};

export default async function ApplyPage({ searchParams }: PageProps<"/app/apply">) {
  const { step, params } = readWizard(await searchParams);
  const context = await getApplyContext();
  const { eligibility } = context;
  const limits = limitsOf(eligibility);

  if (step !== "eligibility" && !eligibility.eligible) {
    redirect(applyHref("eligibility"));
  }
  const choice = clampedChoice(params, limits);
  if ((step === "consent" || step === "check" || step === "review") && !choice) {
    redirect(applyHref("amount", params));
  }

  // The back button on every step: the step before, with what the member typed kept in the address.
  const back: Record<StepId, Route> = {
    eligibility: "/app" as Route,
    amount: applyHref("eligibility"),
    consent: applyHref("amount", params),
    check: applyHref("consent", params),
    review: applyHref("check", params),
  };

  let content: ReactNode;
  switch (step) {
    case "eligibility":
      content = <EligibilityStep eligibility={eligibility} />;
      break;
    case "amount":
      content = (
        <AmountForm
          {...limits}
          maxMonthlyCents={eligibility.maxMonthlyInstalmentCents}
          initialAmount={params.amount ?? limits.minDollars}
          initialTerm={params.term ?? Math.round((limits.minTerm + limits.maxTerm) / 2)}
          initialPurpose={params.purpose ?? ""}
          initialDueDay={params.dueDay ?? null}
          suggestedDueDay={context.suggestedDueDay}
        />
      );
      break;
    case "consent":
      content = (
        <ConsentStep
          consent={context.consent}
          fields={{ amount: choice?.amount ?? 0, term: choice?.term ?? 0, purpose: choice?.purpose ?? "", dueDay: choice?.dueDay ?? null }}
        />
      );
      break;
    case "check": {
      const card = params.assessment ? await getAssessment(params.assessment) : null;
      if (!card) {
        redirect(applyHref("consent", params));
      }
      content = <CheckResult card={card} params={{ ...params, ...choice }} />;
      break;
    }
    case "review": {
      const review = params.assessment ? await getReviewContext(params.assessment) : null;
      if (!review?.card.recommended) {
        redirect(params.assessment ? applyHref("check", params) : applyHref("consent", params));
      }
      content = <ReviewStep review={review} params={{ ...params, ...choice }} />;
      break;
    }
  }

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <Button asChild variant="ghost" size="sm" className="-ml-3 min-h-11 self-start">
        <Link href={back[step]}>
          <ArrowLeft aria-hidden="true" />
          Back
        </Link>
      </Button>
      <ApplyProgress current={step} />
      <PageHeader eyebrow="Borrow" title={HEADINGS[step].title} description={HEADINGS[step].description} />
      {content}
    </div>
  );
}
