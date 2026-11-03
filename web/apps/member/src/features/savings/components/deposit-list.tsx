import { formatDateTime, formatMoney } from "@paypal-seed/ui/lib/format";
import { DEPOSIT_SOURCE, type Deposit } from "../model";

/**
 * The latest money that reached a goal, newest first. `null` means the list could not be read, which is said calmly
 * instead of looking like "no deposits".
 */
export function DepositList({ deposits }: { deposits: Deposit[] | null }) {
  if (deposits === null) {
    return <p className="text-sm text-body">We could not load your deposits just now. Your saved total above is right.</p>;
  }
  if (deposits.length === 0) {
    return <p className="text-sm text-body">No deposits yet. They show up here once PayPal confirms them.</p>;
  }
  return (
    <ul className="flex flex-col divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
      {deposits.map((deposit) => (
        <li key={`${deposit.creditedAt}-${deposit.source}-${deposit.amountCents}`} className="flex items-start justify-between gap-6 p-4">
          <div className="flex flex-col gap-1">
            <p className="text-base font-medium text-ink tabular-nums">{formatMoney(deposit.amountCents)}</p>
            <p className="text-xs text-muted-ink">{DEPOSIT_SOURCE[deposit.source]}</p>
          </div>
          <p className="text-xs text-muted-ink">{formatDateTime(deposit.creditedAt)}</p>
        </li>
      ))}
    </ul>
  );
}
