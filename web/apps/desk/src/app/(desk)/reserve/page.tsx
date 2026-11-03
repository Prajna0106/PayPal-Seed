import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Alert, AlertDescription, AlertTitle } from "@paypal-seed/ui/components/alert";
import { ShieldCheck } from "lucide-react";
import type { Metadata } from "next";
import { Suspense } from "react";
import { z } from "zod";
import { CoverList, CoverListSkeleton } from "@/features/reserve/components/cover-list";
import { FundOverview, FundOverviewSkeleton } from "@/features/reserve/components/fund-overview";
import { RaiseForm } from "@/features/reserve/components/raise-form";
import { readCoverFilter } from "@/features/reserve/model";
import { newIdempotencyKey } from "@/features/reserve/server/keys";
import { hasRole, requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Reserve" };

const uuid = z.uuid();

/** A link may pre-fill the form; anything that is not a UUID is ignored rather than shown back. */
function pick(value: string | string[] | undefined): string | undefined {
  const first = Array.isArray(value) ? value[0] : value;
  return first && uuid.safeParse(first).success ? first : undefined;
}

export default async function ReservePage({ searchParams }: PageProps<"/reserve">) {
  const [params, session] = await Promise.all([searchParams, requireSession()]);
  const filter = readCoverFilter(params.status);
  const before = pick(params.before);
  const canRaise = hasRole(session, "OFFICER") || hasRole(session, "ADMIN");
  const canDecide = hasRole(session, "OFFICER") || hasRole(session, "CHECKER");
  const defaults = { memberId: pick(params.memberId), loanId: pick(params.loanId), hardshipCaseId: pick(params.hardshipCaseId) };

  return (
    <>
      <PageHeader
        eyebrow="Community fund"
        title="Reserve"
        description="Gifts from donors sit in a fund that pays down a member's loan when a new date is not enough. Two officers decide every cover request."
      />

      <Alert>
        <ShieldCheck aria-hidden="true" />
        <AlertTitle>The fund pays the loan, not the member</AlertTitle>
        <AlertDescription>
          An approved cover moves money from the fund to the loan&apos;s balance on the ledger. No cash goes to the member, and the member never
          decides.
        </AlertDescription>
      </Alert>

      <Suspense fallback={<FundOverviewSkeleton />}>
        <FundOverview />
      </Suspense>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
        <div className="flex min-w-0 flex-col gap-4">
          <h2 className="text-display-sm text-ink">Cover requests</h2>
          <Suspense key={`${filter}:${before ?? ""}`} fallback={<CoverListSkeleton />}>
            <CoverList filter={filter} before={before} canDecide={canDecide} />
          </Suspense>
        </div>
        <aside aria-label="Raise a cover request" className="xl:sticky xl:top-20 xl:self-start">
          <RaiseForm idempotencyKey={newIdempotencyKey()} defaults={defaults} canRaise={canRaise} />
        </aside>
      </div>
    </>
  );
}
