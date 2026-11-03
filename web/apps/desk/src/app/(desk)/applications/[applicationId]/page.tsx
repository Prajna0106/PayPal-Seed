import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowLeft } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Suspense } from "react";
import { z } from "zod";
import { ApplicationActions } from "@/features/applications/components/application-actions";
import { ApplicationSummary } from "@/features/applications/components/application-summary";
import { ApprovalsPanel } from "@/features/applications/components/approvals-panel";
import { FairCheckSummary } from "@/features/applications/components/fair-check-summary";
import { PayoutPanel } from "@/features/applications/components/payout-panel";
import { StatusTimeline } from "@/features/applications/components/status-timeline";
import {
  APPLICATION_STATUSES,
  buildTimeline,
  explainPayoutIssue,
  RETURNED_AFTER_DISBURSEMENT,
  shortRef,
} from "@/features/applications/model";
import {
  applicationById,
  fairCheckForApplication,
  staffDirectory,
} from "@/features/applications/server/queries";
import { newIdempotencyKey } from "@/features/hardship/server/keys";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Loan application" };

async function FairCheck({ pending }: { pending: ReturnType<typeof fairCheckForApplication> }) {
  return <FairCheckSummary card={await pending} />;
}

export default async function ApplicationPage({ params }: { params: Promise<{ applicationId: string }> }) {
  const { applicationId } = await params;
  if (!z.uuid().safeParse(applicationId).success) {
    notFound();
  }
  const session = await requireSession();

  // The fair check is the slowest read (a full list); start it now and stream it in behind the rest.
  const fairCheck = fairCheckForApplication(applicationId);
  const [application, staff] = await Promise.all([applicationById(applicationId), staffDirectory()]);
  if (!application) {
    notFound();
  }

  const staffName = (staffId: string) =>
    staff.get(staffId) ?? (staffId === session.staffId ? session.name : `Officer ${shortRef(staffId)}`);
  const status = APPLICATION_STATUSES[application.status];
  const summary = `${formatMoney(application.requestedAmountCents)} over ${application.termMonths} months`;
  const awaitingSignature = application.status === "SUBMITTED" || application.status === "PENDING_SECOND_APPROVAL";
  const signStep = application.status === "SUBMITTED" ? "first" : "second";
  // An approved application with a payoutIssue can be paid again, or ended: the backend allows reject only then.
  const payoutRetry = application.status === "APPROVED" && Boolean(application.payoutIssue);
  const payoutReturned = application.payoutIssue?.toUpperCase() === RETURNED_AFTER_DISBURSEMENT;
  const signedFirstByMe =
    application.status === "PENDING_SECOND_APPROVAL" && application.firstApproverId === session.staffId;

  return (
    <>
      <PageHeader
        eyebrow={
          <span className="inline-flex items-center gap-2">
            Loan application <span className="font-mono normal-case tracking-normal">{shortRef(applicationId)}</span>
          </span>
        }
        title={summary}
        description={`Member ${shortRef(application.memberId)}. Read the fair check, then sign, reject or pay out.`}
        actions={
          <Button asChild variant="ghost" size="sm">
            <Link href={"/applications" as Route}>
              <ArrowLeft aria-hidden="true" />
              Queue
            </Link>
          </Button>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <StatusPill tone={status.tone}>{status.label}</StatusPill>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="flex min-w-0 flex-col gap-6">
          <ApplicationSummary application={application} />
          <Suspense fallback={<Skeleton className="h-64 rounded-xl" aria-label="Loading the fair check" />}>
            <FairCheck pending={fairCheck} />
          </Suspense>
          <StatusTimeline steps={buildTimeline(application, staffName)} />
        </div>
        <aside className="flex flex-col gap-6 xl:sticky xl:top-20 xl:self-start" aria-label="Decision">
          <ApprovalsPanel application={application} staffName={staffName} currentStaffId={session.staffId}>
            {awaitingSignature ? (
              <ApplicationActions
                applicationId={applicationId}
                keys={{ approve: newIdempotencyKey(), reject: newIdempotencyKey() }}
                summary={summary}
                show={{ sign: true, reject: true }}
                signStep={signStep}
                {...(signedFirstByMe ? { signBlocked: "A different officer must sign this one. You signed the first approval." } : {})}
              />
            ) : payoutRetry ? (
              <ApplicationActions
                applicationId={applicationId}
                keys={{ approve: newIdempotencyKey(), reject: newIdempotencyKey() }}
                summary={summary}
                show={{ sign: false, reject: true }}
                signStep="second"
              />
            ) : null}
          </ApprovalsPanel>
          <PayoutPanel
            applicationId={applicationId}
            status={application.status}
            loanId={application.loanId ?? null}
            payoutIssueText={application.payoutIssue ? explainPayoutIssue(application.payoutIssue) : null}
            payoutReturned={payoutReturned}
            summary={summary}
            disburseKey={newIdempotencyKey()}
          />
        </aside>
      </div>
    </>
  );
}
