import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowLeft, ArrowUpRight } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";
import { shortRef } from "@/features/applications/model";
import { FairCheckPanel } from "@/features/fair-checks/components/fair-check-panel";
import { assessmentById } from "@/features/fair-checks/server/queries";
import { newIdempotencyKey } from "@/features/hardship/server/keys";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Fair check" };

export default async function FairCheckPage({ params }: { params: Promise<{ assessmentId: string }> }) {
  const { assessmentId } = await params;
  if (!z.uuid().safeParse(assessmentId).success) {
    notFound();
  }
  await requireSession();
  const card = await assessmentById(assessmentId);
  if (!card) {
    notFound();
  }

  return (
    <>
      <PageHeader
        eyebrow={
          <span className="inline-flex items-center gap-2">
            Fair check <span className="font-mono normal-case tracking-normal">{shortRef(assessmentId)}</span>
          </span>
        }
        title={`${formatMoney(card.requestedAmountCents)} over ${card.requestedTermMonths} months`}
        description={`Member ${shortRef(card.memberId)}. Evidence first, then your decision.`}
        actions={
          <>
            <Button asChild variant="ghost" size="sm">
              <Link href={"/fair-checks" as Route}>
                <ArrowLeft aria-hidden="true" />
                Queue
              </Link>
            </Button>
            {card.applicationId ? (
              <Button asChild variant="outline" size="sm">
                <Link href={`/applications/${card.applicationId}` as Route}>
                  Open the application
                  <ArrowUpRight aria-hidden="true" />
                </Link>
              </Button>
            ) : null}
          </>
        }
      />
      <div className="max-w-3xl">
        <FairCheckPanel card={card} idempotencyKey={newIdempotencyKey()} />
      </div>
    </>
  );
}
