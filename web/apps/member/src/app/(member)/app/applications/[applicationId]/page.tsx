import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Button } from "@paypal-seed/ui/components/button";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowLeft, Clock, LifeBuoy } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import type { ReactNode } from "react";
import { z } from "zod";
import { ApplicationTimeline } from "@/features/lending/components/application-timeline";
import { CancelCoolingOff } from "@/features/lending/components/cancel-cooling-off";
import { getApplication } from "@/features/lending/server/queries";
import { APPLICATION_STATUS, RETURNED_NOTE } from "@/features/lending/status";

export const metadata: Metadata = { title: "Your application" };

export default async function ApplicationPage({ params }: PageProps<"/app/applications/[applicationId]">) {
  const id = z.uuid().safeParse((await params).applicationId);
  const application = id.success ? await getApplication(id.data) : null;
  if (!application) {
    notFound();
  }
  const status = APPLICATION_STATUS[application.status];
  const { coolingOff } = application;

  return (
    <div className="flex flex-col gap-10">
      <Button asChild variant="ghost" size="sm" className="-ml-3 min-h-11 self-start">
        <Link href={"/app/applications" as Route}>
          <ArrowLeft aria-hidden="true" />
          Your applications
        </Link>
      </Button>

      <PageHeader
        eyebrow={`Sent ${formatDateTime(application.createdAt, { dateStyle: "medium" })}`}
        title={`${formatMoney(application.amountCents)} over ${application.termMonths} months`}
        description={status.note}
        actions={<StatusPill tone={status.tone}>{status.label}</StatusPill>}
      />

      {application.payoutReturned && application.status === "APPROVED" ? (
        <Notice icon={<LifeBuoy aria-hidden="true" />} title="The money did not reach your PayPal account">
          {RETURNED_NOTE} Your loan stays approved, and there is nothing for you to do right now.
        </Notice>
      ) : application.payoutTrouble && application.status === "APPROVED" ? (
        <Notice icon={<LifeBuoy aria-hidden="true" />} title="We are sorting out the payment">
          Your loan is approved, but the money did not reach you the first time. Someone at Seed is on it. You do not need to do anything,
          and we will get in touch if we need a detail from you.
        </Notice>
      ) : null}

      <div className="grid gap-6 lg:grid-cols-[1fr_1.2fr]">
        <section aria-labelledby="progress" className="flex flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6">
          <h2 id="progress" className="text-title-sm font-medium text-ink">
            Where it is
          </h2>
          <ApplicationTimeline status={application.status} />
        </section>

        <section aria-labelledby="details" className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6">
          <h2 id="details" className="text-title-sm font-medium text-ink">
            The details
          </h2>
          <dl className="flex flex-col divide-y divide-hairline">
            <Row label="Amount" value={formatMoney(application.amountCents)} />
            <Row label="Monthly payments" value={String(application.termMonths)} />
            {application.monthlyCents ? <Row label="Each payment" value={formatMoney(application.monthlyCents)} /> : null}
            {application.dueDay ? <Row label="Payment day" value={`Day ${application.dueDay} of the month`} /> : null}
            <Row label="Interest and fees" value="None" />
            <Row label="What it is for" value={application.purpose} />
          </dl>
          {application.loanId ? (
            <Button asChild className="h-11 self-start">
              <Link href={`/app/loans/${application.loanId}` as Route}>See your repayment plan</Link>
            </Button>
          ) : null}
        </section>
      </div>

      {application.status === "DISBURSED" && coolingOff ? (
        coolingOff.open ? (
          <section aria-labelledby="cooling" className="flex flex-col gap-3 rounded-2xl border border-hairline bg-canvas-soft p-6">
            <h2 id="cooling" className="flex items-center gap-2 text-title-sm font-medium text-ink">
              <Clock aria-hidden="true" className="size-4 text-muted-ink" />
              You can still return this loan
            </h2>
            <p className="text-body text-pretty">
              For 48 hours after the money arrives you can return it, at no cost. This window closes{" "}
              <time dateTime={coolingOff.expiresAt} className="font-medium text-ink">
                {coolingOff.relative}
              </time>{" "}
              ({formatDateTime(coolingOff.expiresAt)}).
            </p>
            <CancelCoolingOff applicationId={application.id} />
          </section>
        ) : (
          <p className="text-body text-pretty">
            The 48-hour window to return this loan has ended. You can still pay it off early whenever you like, at no cost.
          </p>
        )
      ) : null}

      {application.status === "DISBURSING" ? (
        <Notice icon={<Clock aria-hidden="true" />} title="Why you cannot cancel right now">
          The money is already on its way, so this cannot be cancelled at this moment. When it arrives you will have 48 hours to return it,
          at no cost.
        </Notice>
      ) : null}

      {application.status === "SUBMITTED" || application.status === "PENDING_SECOND_APPROVAL" || application.status === "APPROVED" ? (
        <p className="text-body text-pretty">
          Changed your mind? Once the money arrives you will have 48 hours to return it, at no cost.
        </p>
      ) : null}

      {application.status === "REJECTED" ? <Declined note={application.rejectionNote} /> : null}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-6 py-3 first:pt-0 last:pb-0">
      <dt className="text-body">{label}</dt>
      <dd className="text-right font-medium text-ink tabular-nums">{value}</dd>
    </div>
  );
}

function Notice({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <aside className="flex gap-4 rounded-2xl border border-hairline bg-canvas-soft p-6">
      <span className="mt-0.5 text-muted-ink [&_svg]:size-5">{icon}</span>
      <div className="flex flex-col gap-1.5">
        <h2 className="text-title-sm font-medium text-ink">{title}</h2>
        <p className="text-body text-pretty">{children}</p>
      </div>
    </aside>
  );
}

function Declined({ note }: { note: string | null }) {
  return (
    <section aria-labelledby="next-steps" className="flex flex-col gap-4 rounded-2xl border border-hairline bg-surface p-6">
      <h2 id="next-steps" className="text-display-sm text-ink">
        What you can do next
      </h2>
      {note ? (
        <p className="text-body text-pretty">
          <span className="font-medium text-ink">A note from our team: </span>
          {note}
        </p>
      ) : null}
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-body">
        <li>Keep saving. Your streak and your record keep growing, and that opens more.</li>
        <li>Try a smaller amount or a longer term. A gentler payment is often easier to approve.</li>
        <li>If you think we got something wrong, you can tell us on the fair check step when you apply again.</li>
      </ul>
      <div className="flex flex-col gap-3 sm:flex-row">
        <Button asChild className="h-11">
          <Link href={"/app/savings" as Route}>Go to my savings</Link>
        </Button>
        <Button asChild variant="outline" className="h-11">
          <Link href={"/app/apply" as Route}>Look at borrowing again</Link>
        </Button>
      </div>
    </section>
  );
}
