import { cn } from "@paypal-seed/ui/lib/utils";
import { formatMoney, type MoneyFormat } from "@paypal-seed/ui/lib/format";

export interface MoneyProps extends MoneyFormat {
  /** Integer cents, exactly as the API returned them. */
  cents: number;
  className?: string;
}

export function Money({ cents, className, ...format }: MoneyProps) {
  return <span className={cn("tabular-nums", className)}>{formatMoney(cents, format)}</span>;
}
