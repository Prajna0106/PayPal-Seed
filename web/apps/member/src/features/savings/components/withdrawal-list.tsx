import { StatusPill } from "@paypal-seed/ui/brand/status-pill";
import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { type Withdrawal, WITHDRAWAL_STATUS } from "../model";

/** Money taken out of the goal, newest first, in words: on its way, sent, or back in the goal. */
export function WithdrawalList({ withdrawals }: { withdrawals: Withdrawal[] }) {
  if (withdrawals.length === 0) {
    return <p className="text-sm text-body">Nothing taken out yet.</p>;
  }
  return (
    <ul className="flex flex-col divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
      {withdrawals.map((withdrawal) => {
        const status = WITHDRAWAL_STATUS[withdrawal.status];
        return (
          <li key={withdrawal.id} className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2 p-4">
            <div className="flex flex-col gap-1">
              <p className="text-base font-medium text-ink tabular-nums">{formatMoney(withdrawal.amountCents)}</p>
              <p className="text-xs text-muted-ink">{formatDateTime(withdrawal.createdAt)}</p>
            </div>
            <div className="flex flex-col items-start gap-1 sm:items-end">
              <StatusPill tone={status.tone}>{status.label}</StatusPill>
              <p className="max-w-xs text-xs text-body sm:text-right">{status.hint}</p>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
