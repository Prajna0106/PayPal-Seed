import { isProblem, problemMessage } from "@paypal-seed/api-client/problem";
import type { InstalmentLine, VersionView } from "@paypal-seed/api-client/types";
import { BusinessDate } from "@paypal-seed/ui/brand/business-date";
import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { Kpi } from "@paypal-seed/ui/brand/kpi";
import { Money } from "@paypal-seed/ui/brand/money";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { StatusPill, type StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@paypal-seed/ui/components/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@paypal-seed/ui/components/table";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CircleAlert, CircleCheck, Landmark } from "lucide-react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { DeskTimeline } from "@/features/loans/components/desk-timeline";
import { deskIntegrity, deskSchedule, deskTimeline, deskVersions } from "@/features/loans/server/queries";

export const metadata: Metadata = { title: "Loan" };

const LINE_TONES: Record<string, StatusTone> = {
  PAID: "success",
  PAID_LATE: "warning",
  PREPAID: "success",
  DUE: "info",
  SCHEDULED: "neutral",
  PARTIALLY_PAID: "info",
  IN_GRACE: "warning",
  LATE: "danger",
};

const REASONS: Record<string, string> = {
  ORIGINAL: "Original plan",
  HARDSHIP_MOVE_DATE: "Hardship: date moved",
  HARDSHIP_PAUSE: "Hardship: one-month pause",
  HARDSHIP_REAMORTIZE: "Hardship: smaller, longer plan",
  CORRECTION: "Correction",
};

function lineLabel(status: string | undefined): string {
  return (status ?? "").replace(/_/g, " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

export default async function LoanPage({ params }: PageProps<"/loans/[loanId]">) {
  const { loanId } = await params;
  let schedule, versions: VersionView[], integrity;
  try {
    [schedule, versions, integrity] = await Promise.all([deskSchedule(loanId), deskVersions(loanId), deskIntegrity(loanId)]);
  } catch (error) {
    if (isProblem(error) && (error.status === 404 || error.status === 400)) {
      notFound();
    }
    return (
      <>
        <PageHeader eyebrow="Loan" title="Loan" />
        <EmptyState title="This loan is unavailable" description={isProblem(error) ? problemMessage(error) : "We could not reach the Seed backend."} />
      </>
    );
  }

  const lines: InstalmentLine[] = schedule.instalments ?? [];
  const payload = deskTimeline(loanId, schedule, versions);
  return (
    <>
      <PageHeader
        eyebrow={<span>Loan <span className="font-mono normal-case tracking-normal">{loanId.slice(0, 8)}</span></span>}
        title={`${formatMoney(schedule.principalCents ?? 0)} over ${lines.length} payments`}
        description={`Plan version ${schedule.version ?? 1} · ${REASONS[schedule.reason ?? ""] ?? schedule.reason}. Business date ${schedule.businessDate ?? "—"}.`}
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Outstanding" value={<Money cents={schedule.outstandingCents ?? 0} />} icon={<Landmark />} />
        <Kpi label="Status" value={<span className="text-3xl">{lineLabel(schedule.loanStatus)}</span>} hint={schedule.collectionMode ? `Collection: ${lineLabel(schedule.collectionMode)}` : undefined} />
        <Kpi label="Late bucket" value={<span className="text-3xl">{(schedule.bucket ?? "CURRENT").replace("DPD_", "").replace(/_/g, "–")}</span>} hint={`${schedule.dpd ?? 0} days past due`} />
        <Kpi
          label="Ledger check"
          value={<span className="text-3xl">{integrity.consistent ? "Consistent" : "Mismatch"}</span>}
          icon={integrity.consistent ? <CircleCheck /> : <CircleAlert />}
          hint={`Receivable ${formatMoney(integrity.ledgerReceivableCents ?? 0)} vs outstanding ${formatMoney(integrity.outstandingCents ?? 0)}`}
        />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Timeline</CardTitle>
          <CardDescription>
            Each payment coloured by its status. After a hardship change the previous plan shows as dashed ghosts, with arrows to where each payment moved. Shaded bands are grace windows.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DeskTimeline payload={payload} today={schedule.businessDate ?? lines[0]?.dueDate ?? "2026-01-01"} />
        </CardContent>
      </Card>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
        <Card className="min-w-0">
          <CardHeader>
            <CardTitle>Payments</CardTitle>
            <CardDescription>Current plan, version {schedule.version ?? 1}</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>No.</TableHead>
                  <TableHead>Due</TableHead>
                  <TableHead className="text-right">Amount</TableHead>
                  <TableHead className="text-right">Paid</TableHead>
                  <TableHead className="text-right">Remaining</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {lines.map((line) => (
                  <TableRow key={line.seq}>
                    <TableCell className="tabular-nums">{line.seq}</TableCell>
                    <TableCell>{line.dueDate ? <BusinessDate value={line.dueDate} /> : "—"}</TableCell>
                    <TableCell className="text-right"><Money cents={line.amountCents ?? 0} /></TableCell>
                    <TableCell className="text-right"><Money cents={line.paidCents ?? 0} /></TableCell>
                    <TableCell className="text-right"><Money cents={line.remainingCents ?? 0} /></TableCell>
                    <TableCell>
                      <StatusPill tone={LINE_TONES[line.status ?? ""] ?? "neutral"}>{lineLabel(line.status)}</StatusPill>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        <Card className="min-w-0">
          <CardHeader>
            <CardTitle>Plan versions</CardTitle>
            <CardDescription>Versions are never edited; every change is a new one, with what caused it.</CardDescription>
          </CardHeader>
          <CardContent>
            <ol className="flex flex-col gap-4">
              {[...versions].reverse().map((v) => (
                <li key={v.version} className="flex flex-col gap-2 rounded-xl border border-hairline p-4">
                  <div className="flex items-center justify-between gap-2">
                    <p className="font-medium text-ink">
                      Version {v.version} <span className="font-normal text-body">· {REASONS[v.reason ?? ""] ?? v.reason}</span>
                    </p>
                    {v.createdOn ? <BusinessDate value={v.createdOn} className="text-sm text-muted-ink" /> : null}
                  </div>
                  <p className="truncate font-mono text-xs text-muted-ink" title={v.causedBy}>{v.causedBy}</p>
                  {v.diffFromPrevious ? (
                    <div className="grid grid-cols-2 gap-3 text-sm">
                      <div>
                        <p className="text-xs text-muted-ink">Removed</p>
                        {(v.diffFromPrevious.removed ?? []).map((s) => (
                          <p key={`r-${s.seq}`} className="text-danger line-through tabular-nums">
                            {s.dueDate ? <BusinessDate value={s.dueDate} dateStyle="short" /> : null} · {formatMoney(s.amountCents ?? 0)}
                          </p>
                        ))}
                      </div>
                      <div>
                        <p className="text-xs text-muted-ink">Added</p>
                        {(v.diffFromPrevious.added ?? []).map((s) => (
                          <p key={`a-${s.seq}`} className="text-success tabular-nums">
                            {s.dueDate ? <BusinessDate value={s.dueDate} dateStyle="short" /> : null} · {formatMoney(s.amountCents ?? 0)}
                          </p>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
