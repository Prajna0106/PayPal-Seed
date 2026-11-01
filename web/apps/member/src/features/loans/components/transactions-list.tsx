import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowDownLeft, ArrowUpRight, Undo2 } from "lucide-react";
import type { Transaction } from "../model";

const KIND: Record<Transaction["type"], { label: string; sign: string; Icon: typeof ArrowDownLeft }> = {
  RECEIPT: { label: "Payment received", sign: "", Icon: ArrowDownLeft },
  REVERSAL: { label: "Payment reversed", sign: "−", Icon: Undo2 },
  OVERPAYMENT_REFUND: { label: "Extra money refunded", sign: "−", Icon: ArrowUpRight },
};

const SOURCE: Record<string, string> = {
  AUTOPAY: "Autopay",
  AUTOPAY_RETRY: "Autopay retry",
  CHECKOUT: "PayPal checkout",
  PAYMENT_LINK: "Payment link",
  RECONCILER: "PayPal",
  PAYPAL: "PayPal",
  SEED: "Seed",
};

/** Money that moved on this loan, newest first. */
export function TransactionsList({ transactions }: { transactions: Transaction[] }) {
  if (transactions.length === 0) {
    return <EmptyState title="No payments yet" description="Payments you make with PayPal show up here." />;
  }
  const newestFirst = [...transactions].reverse();
  return (
    <ul className="divide-y divide-hairline overflow-hidden rounded-2xl border border-hairline bg-surface">
      {newestFirst.map((t) => {
        const kind = KIND[t.type];
        return (
          <li key={t.id} className="flex items-center gap-4 px-6 py-4">
            <span className="flex size-9 items-center justify-center rounded-full bg-surface-strong text-ink" aria-hidden="true">
              <kind.Icon className="size-4" />
            </span>
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="text-sm font-medium text-ink">{kind.label}</span>
              <span className="text-xs text-muted-ink">
                {SOURCE[t.source] ?? "PayPal"} · {formatBusinessDate(t.effectiveDate, "medium")}
              </span>
            </div>
            <span className="text-sm font-medium text-ink tabular-nums">
              {kind.sign}
              {formatMoney(t.amountCents, { showCents: t.amountCents % 100 !== 0 })}
            </span>
          </li>
        );
      })}
    </ul>
  );
}
