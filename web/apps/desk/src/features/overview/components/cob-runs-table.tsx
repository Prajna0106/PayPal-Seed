import type { CobRun } from "@paypal-seed/api-client/types";
import { BusinessDate } from "@paypal-seed/ui/brand/business-date";
import { StatusPill, type StatusTone } from "@paypal-seed/ui/brand/status-pill";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@paypal-seed/ui/components/table";

const TONES: Record<string, StatusTone> = { COMPLETED: "success", RUNNING: "info", DEGRADED: "danger" };

/** Close-of-business runs: one per business date, in strict order. */
export function CobRunsTable({ runs }: { runs: CobRun[] }) {
  if (runs.length === 0) {
    return <p className="text-sm text-muted-ink">No close of business has run yet. Operations can start a catch-up.</p>;
  }
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Business date</TableHead>
          <TableHead>Status</TableHead>
          <TableHead>Mode</TableHead>
          <TableHead className="text-right">Loans</TableHead>
          <TableHead className="text-right">Failed</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {runs.map((run) => (
          <TableRow key={run.businessDate}>
            <TableCell>{run.businessDate ? <BusinessDate value={run.businessDate} /> : "—"}</TableCell>
            <TableCell>
              <StatusPill tone={TONES[run.status ?? ""] ?? "neutral"}>{run.status}</StatusPill>
            </TableCell>
            <TableCell className="text-muted-ink">{run.mode}</TableCell>
            <TableCell className="text-right tabular-nums">{run.loansProcessed ?? 0}</TableCell>
            <TableCell className={run.loansFailed ? "text-right text-danger tabular-nums" : "text-right tabular-nums"}>
              {run.loansFailed ?? 0}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
