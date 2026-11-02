import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { formatBusinessDate } from "@paypal-seed/ui/lib/format";
import { PiggyBank, ScrollText, Wallet } from "lucide-react";
import type { ReactNode } from "react";
import { HelpBanner } from "@/features/loans/components/help-banner";
import { HELP_REVIEWING } from "../copy";
import { type Home, sectionState } from "../model";
import { ApplicationCard } from "./application-card";
import { BorrowCard } from "./borrow-card";
import { RecordEmpty, SavingsEmpty } from "./empty-cards";
import { FadeUp, HomeMotion } from "./home-motion";
import { LoanSnapshot } from "./loan-snapshot";
import { RecordCard } from "./record-card";
import { SavingsCard } from "./savings-card";
import { isUnavailable, SectionNote, SectionUnavailable } from "./section-card";
import { StartSaving } from "./start-saving";

/**
 * Home from the one summary call. Each section stands alone: a missing one becomes a calm note or an invitation and
 * the others still render. Nothing here is computed from money or risk; it only arranges what the backend returned.
 */
export function HomeView({ home, fallbackName }: { home: Home; fallbackName: string }) {
  const name = home.profile?.displayName || fallbackName;
  const savings = home.savings ?? null;
  const lending = home.lending ?? null;
  const record = home.creditRecord ?? null;
  const hardship = home.hardship ?? null;

  const savingsState = sectionState(home, "savings");
  const lendingState = sectionState(home, "lending");
  const recordState = sectionState(home, "creditRecord");
  const hardshipState = sectionState(home, "hardship");

  const activeLoan = lending?.activeLoan ?? null;
  const application = lending?.openApplication ?? null;
  const notSaving = savings === null || (savings.totalSaved.cents === 0 && savings.currentStreakMonths === 0 && (savings.goals?.length ?? 0) === 0);
  const brandNew = savingsState !== "unavailable" && notSaving && !activeLoan && !application;

  const helpLoanId = hardship?.loanId ?? null;
  const helpState: "offer" | "reviewing" | null =
    helpLoanId === null ? null : hardship?.offerWaiting ? "offer" : hardship?.status && HELP_REVIEWING.has(hardship.status) ? "reviewing" : null;

  const cards: { key: string; node: ReactNode }[] = [];

  if (!brandNew) {
    let node: ReactNode = <SavingsEmpty />;
    if (isUnavailable(savingsState)) {
      node = <SectionUnavailable section="savings" title="Savings" icon={<PiggyBank aria-hidden="true" />} />;
    } else if (savings && !notSaving) {
      node = <SavingsCard savings={savings} />;
    }
    cards.push({ key: "savings", node });
  }

  if (isUnavailable(lendingState)) {
    cards.push({
      key: "lending",
      node: <SectionUnavailable section="lending" title="Loan and Borrow" icon={<Wallet aria-hidden="true" />} />,
    });
  } else {
    if (activeLoan) {
      cards.push({ key: "loan", node: <LoanSnapshot loan={activeLoan} /> });
    }
    cards.push({ key: "borrow", node: <BorrowCard lending={lending} /> });
  }

  if (application) {
    cards.push({ key: "application", node: <ApplicationCard application={application} /> });
  }

  let recordNode: ReactNode = <RecordEmpty />;
  if (isUnavailable(recordState)) {
    recordNode = <SectionUnavailable section="creditRecord" title="Your Seed record" icon={<ScrollText aria-hidden="true" />} />;
  } else if (record) {
    recordNode = <RecordCard record={record} />;
  }
  cards.push({ key: "record", node: recordNode });

  return (
    <HomeMotion>
      <div className="flex flex-col gap-8">
        <FadeUp>
          <PageHeader
            eyebrow={`Today is ${formatBusinessDate(home.generatedOn, "weekday")}`}
            title={`Hi ${name}`}
            description="Your savings, your loan and anything we can help with. No fees, ever."
          />
        </FadeUp>

        {helpState && helpLoanId ? (
          <FadeUp delay={0.05}>
            <HelpBanner loanId={helpLoanId} state={helpState} />
          </FadeUp>
        ) : null}
        {isUnavailable(hardshipState) ? <SectionNote section="hardship" /> : null}

        {brandNew ? (
          <FadeUp delay={0.08}>
            <StartSaving />
          </FadeUp>
        ) : null}

        <div className="grid items-stretch gap-4 md:grid-cols-2">
          {cards.map(({ key, node }, index) => (
            <FadeUp key={key} delay={0.08 + index * 0.06} className="h-full">
              {node}
            </FadeUp>
          ))}
        </div>
      </div>
    </HomeMotion>
  );
}
