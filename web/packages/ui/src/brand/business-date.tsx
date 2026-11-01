import { cn } from "@paypal-seed/ui/lib/utils";
import { type BusinessDateStyle, formatBusinessDate } from "@paypal-seed/ui/lib/format";

export interface BusinessDateProps {
  /** A plain `YYYY-MM-DD` business date. */
  value: string;
  dateStyle?: BusinessDateStyle;
  locale?: string;
  className?: string;
}

export function BusinessDate({ value, dateStyle = "medium", locale, className }: BusinessDateProps) {
  return (
    <time dateTime={value} className={cn("tabular-nums", className)}>
      {formatBusinessDate(value, dateStyle, locale)}
    </time>
  );
}
