import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from "@paypal-seed/ui/components/table";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import type { Instalment } from "../model";
import { INSTALMENT_STATUS } from "../status";

/** Every payment in the current plan. The timeline above draws the same rows; this is the readable version. */
export function InstalmentTable({ instalments, version }: { instalments: Instalment[]; version: number }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-hairline bg-surface">
      <Table>
        <TableCaption className="sr-only">
          Payments in your plan{version > 1 ? `, version ${version}` : ""}
        </TableCaption>
        <TableHeader>
          <TableRow>
            <TableHead className="w-12 pl-6">#</TableHead>
            <TableHead>Due</TableHead>
            <TableHead className="text-right">Amount</TableHead>
            <TableHead className="hidden text-right sm:table-cell">Paid</TableHead>
            <TableHead className="pr-6 text-right">Status</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {instalments.map((i) => {
            const status = INSTALMENT_STATUS[i.status];
            return (
              <TableRow key={i.seq}>
                <TableCell className="pl-6 text-muted-ink tabular-nums">{i.seq}</TableCell>
                <TableCell className="text-ink">{formatBusinessDate(i.dueDate, "medium")}</TableCell>
                <TableCell className="text-right text-ink tabular-nums">{formatMoney(i.amountCents)}</TableCell>
                <TableCell className="hidden text-right text-body tabular-nums sm:table-cell">
                  {i.paidCents > 0 ? formatMoney(i.paidCents) : "—"}
                </TableCell>
                <TableCell className="pr-6 text-right">
                  <StatusPill tone={status.tone}>{status.label}</StatusPill>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
