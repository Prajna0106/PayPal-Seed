import { isProblem } from "@paypal-seed/api-client/problem";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { Button } from "@paypal-seed/ui/components/button";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowLeft, ArrowUpRight, Gauge, Users } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { ApprovalPanel } from "@/features/hardship/components/approval-panel";
import { DecisionForm } from "@/features/hardship/components/decision-form";
import { EvidencePanel } from "@/features/hardship/components/evidence-panel";
import { NarrativesPanel } from "@/features/hardship/components/narratives-panel";
import { OptionsPanel } from "@/features/hardship/components/options-panel";
import { RetryButton } from "@/features/hardship/components/retry-button";
import { CASE_STATUSES, OPTION_NAMES, ORIGINS, RISK_LEVELS } from "@/features/hardship/model";
import { newIdempotencyKey } from "@/features/hardship/server/keys";
import { approvalStage, caseDetail } from "@/features/hardship/server/queries";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Hardship case" };

export default async function CasePage({ params }: PageProps<"/hardship/[caseId]">) {
  const { caseId } = await params;
  const [session, detail] = await Promise.all([
    requireSession(),
    caseDetail(caseId).catch((error: unknown) => {
      if (isProblem(error) && (error.status === 404 || error.status === 400)) {
        notFound();
      }
      throw error;
    }),
  ]);

  const summary = detail.summary ?? {};
  const status = summary.status ? CASE_STATUSES[summary.status] : undefined;
  const level = summary.level ? RISK_LEVELS[summary.level] : undefined;
  const approval = detail.approval ?? null;
  const stage = approvalStage(approval);
  const canMake = stage === "WAITING_FOR_MAKER" && hasRole(session, "OFFICER");
  const canCheck = stage === "WAITING_FOR_CHECKER" && hasRole(session, "CHECKER");
  const canRetry = summary.status === "APPROVED_EXECUTION_FAILED" && (hasRole(session, "OFFICER") || hasRole(session, "ADMIN"));

  return (
    <>
      <PageHeader
        eyebrow={
          <span className="inline-flex items-center gap-2">
            Hardship case <span className="font-mono normal-case tracking-normal">{caseId.slice(0, 8)}</span>
          </span>
        }
        title={
          summary.targetAmountCents !== undefined && summary.targetDueDate
            ? `${formatMoney(summary.targetAmountCents)} due ${formatBusinessDate(summary.targetDueDate, "long")}`
            : "Hardship case"
        }
        description={`Payment ${summary.targetSeq ?? "?"} of the loan. ${ORIGINS[summary.origin ?? "PROACTIVE_SCAN"]}, opened ${summary.openedOn ? formatBusinessDate(summary.openedOn) : "recently"}.`}
        actions={
          <>
            <Button asChild variant="ghost" size="sm">
              <Link href="/hardship">
                <ArrowLeft aria-hidden="true" />
                Queue
              </Link>
            </Button>
            {summary.loanId ? (
              <Button asChild variant="outline" size="sm">
                <Link href={`/loans/${summary.loanId}` as Route}>
                  Open loan
                  <ArrowUpRight aria-hidden="true" />
                </Link>
              </Button>
            ) : null}
          </>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        {status ? <StatusPill tone={status.tone}>{status.label}</StatusPill> : null}
        {level ? (
          <StatusPill tone={level.tone} dot={false}>
            <span className="inline-flex items-center gap-1 [&_svg]:size-3.5">
              <Gauge aria-hidden="true" />
              {level.label} · {summary.points ?? 0} pts
            </span>
          </StatusPill>
        ) : null}
        {detail.chosenOption ? (
          <StatusPill tone="ink" dot={false}>
            Member chose {detail.chosenOption} · {OPTION_NAMES[detail.chosenOption] ?? ""}
            {detail.choiceChannel ? ` · by ${detail.choiceChannel === "SMS" ? "SMS" : "app"}` : ""}
          </StatusPill>
        ) : null}
        {detail.closedReason ? <StatusPill tone="neutral">Closed: {detail.closedReason}</StatusPill> : null}
      </div>

      {detail.rubberStampFlag ? (
        <Alert>
          <Gauge aria-hidden="true" />
          <AlertTitle>Your recent decisions were very fast</AlertTitle>
          <AlertDescription>
            Several approvals were made within seconds of opening the evidence. Please read the evidence and the options
            before deciding; a note is expected on your next decisions.
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="flex min-w-0 flex-col gap-6">
          <EvidencePanel signals={detail.signals ?? []} points={summary.points ?? 0} policyVersion={detail.policyVersion} />
          <OptionsPanel options={detail.options ?? []} chosen={detail.chosenOption} revision={detail.offerRevision} />
          <NarrativesPanel narratives={detail.narratives ?? []} />
        </div>
        <aside className="flex flex-col gap-6 xl:sticky xl:top-20 xl:self-start" aria-label="Decision">
          <ApprovalPanel approval={approval} stage={stage}>
            {canMake || canCheck ? (
              <DecisionForm caseId={caseId} idempotencyKey={newIdempotencyKey()} step={canCheck ? "checker" : "maker"} />
            ) : stage === "WAITING_FOR_MAKER" || stage === "WAITING_FOR_CHECKER" ? (
              <p className="flex items-start gap-2 rounded-xl bg-canvas-soft p-3 text-sm text-body [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0">
                <Users aria-hidden="true" />
                {stage === "WAITING_FOR_CHECKER"
                  ? "This needs a checker. Switch persona (bottom left) to Priyam Priyanshu Pradhan, a different officer from the maker."
                  : "This needs a maker. Switch persona (bottom left) to Aira Jena or Prajna Jeet Ojha."}
              </p>
            ) : null}
          </ApprovalPanel>
          {canRetry ? (
            <Alert variant="destructive">
              <AlertTitle>Applying the plan failed</AlertTitle>
              <AlertDescription className="flex flex-col gap-3">
                The option was approved but the loan could not be updated. Nothing changed for the member yet.
                <RetryButton caseId={caseId} />
              </AlertDescription>
            </Alert>
          ) : null}
        </aside>
      </div>
    </>
  );
}
