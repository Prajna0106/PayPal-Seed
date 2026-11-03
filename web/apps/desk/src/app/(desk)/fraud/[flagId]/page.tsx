import { isProblem } from "@paypal-seed/api-client/problem";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { Button } from "@paypal-seed/ui/components/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { formatDateTime } from "@paypal-seed/ui/lib/format";
import { ArrowLeft, CircleAlert, Users } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { AdvisoryNote } from "@/features/fraud/components/advisory-note";
import { EvidenceList } from "@/features/fraud/components/evidence-list";
import { ReviewForm } from "@/features/fraud/components/review-form";
import { FLAG_STATUSES, fraudErrorMessage } from "@/features/fraud/model";
import { newIdempotencyKey } from "@/features/fraud/server/keys";
import { flagDetail, flagEvidence } from "@/features/fraud/server/queries";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Fraud flag" };

export default async function FlagPage({ params }: PageProps<"/fraud/[flagId]">) {
  const { flagId } = await params;
  const session = await requireSession();

  let loaded: Awaited<ReturnType<typeof load>>;
  try {
    loaded = await load(flagId);
  } catch (error) {
    if (isProblem(error) && (error.status === 404 || error.status === 400)) {
      notFound();
    }
    return (
      <>
        <BackButton />
        <Alert variant="destructive">
          <CircleAlert aria-hidden="true" />
          <AlertTitle>This flag could not be loaded</AlertTitle>
          <AlertDescription>{fraudErrorMessage(error)}</AlertDescription>
        </Alert>
      </>
    );
  }

  const { detail, evidence } = loaded;
  const { flag, review } = detail;
  const status = FLAG_STATUSES[flag.status];
  const canReview = flag.status === "OPEN" && (hasRole(session, "OFFICER") || hasRole(session, "ADMIN"));

  return (
    <>
      <PageHeader
        eyebrow={
          <span className="inline-flex items-center gap-2">
            Fraud flag <span className="font-mono normal-case tracking-normal">{flagId.slice(0, 8)}</span>
          </span>
        }
        title={`Total weight ${flag.score} of ${detail.flagThreshold}`}
        description={`Member ${flag.memberId.slice(0, 8)}. Raised ${formatDateTime(flag.raisedAt)}.`}
        actions={<BackButton />}
      />

      <div className="flex flex-wrap items-center gap-2">
        <StatusPill tone={status.tone}>{status.label}</StatusPill>
        {flag.reviewedAt ? <StatusPill tone="neutral" dot={false}>Reviewed {formatDateTime(flag.reviewedAt)}</StatusPill> : null}
      </div>

      <AdvisoryNote />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
        <EvidenceList evidence={evidence} />
        <aside className="flex flex-col gap-6 xl:sticky xl:top-20 xl:self-start" aria-label="Review">
          <Card>
            <CardHeader>
              <CardTitle>Review</CardTitle>
              <CardDescription>
                {review ? "This flag has been reviewed." : "Clear it if there is an ordinary explanation, or confirm it with a note."}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {review ? (
                <dl className="flex flex-col gap-3 text-sm">
                  <div className="flex flex-col gap-0.5">
                    <dt className="text-xs text-muted-ink">Outcome</dt>
                    <dd className="font-medium text-ink">{review.decision === "CLEAR" ? "Cleared" : "Confirmed"}</dd>
                  </div>
                  <div className="flex flex-col gap-0.5">
                    <dt className="text-xs text-muted-ink">When</dt>
                    <dd className="text-ink">{formatDateTime(review.reviewedAt)}</dd>
                  </div>
                  <div className="flex flex-col gap-0.5">
                    <dt className="text-xs text-muted-ink">Note</dt>
                    <dd className="break-words text-body">{review.note ? review.note : "No note."}</dd>
                  </div>
                </dl>
              ) : canReview ? (
                <ReviewForm flagId={flagId} idempotencyKey={newIdempotencyKey()} />
              ) : (
                <p className="flex items-start gap-2 rounded-xl bg-canvas-soft p-3 text-sm text-body [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0">
                  <Users aria-hidden="true" />
                  Only an officer or an admin can review a flag. Switch persona (bottom left) to review this one.
                </p>
              )}
            </CardContent>
          </Card>
        </aside>
      </div>
    </>
  );
}

function BackButton() {
  return (
    <Button asChild variant="ghost" size="sm" className="min-h-11 sm:min-h-8">
      <Link href={"/fraud" as Route}>
        <ArrowLeft aria-hidden="true" />
        Flags
      </Link>
    </Button>
  );
}

/** Both reads start together: the detail holds the review, the evidence holds the facts. */
function load(flagId: string) {
  return Promise.all([flagDetail(flagId), flagEvidence(flagId)]).then(([detail, evidence]) => ({ detail, evidence }));
}
