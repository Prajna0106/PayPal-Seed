import type { ReactNode } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

export interface KpiProps {
  label: ReactNode;
  value: ReactNode;
  /** A short explanation or comparison under the value. */
  hint?: ReactNode;
  icon?: ReactNode;
  className?: string;
}

export function Kpi({ label, value, hint, icon, className }: KpiProps) {
  return (
    <div className={cn("flex flex-col gap-3 rounded-xl border border-hairline bg-surface p-5", className)}>
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-muted-ink">{label}</p>
        {icon ? <span className="text-muted-ink [&_svg]:size-4">{icon}</span> : null}
      </div>
      <p className="font-display text-4xl font-light tracking-tight text-ink tabular-nums">{value}</p>
      {hint ? <p className="text-xs text-muted-ink">{hint}</p> : null}
    </div>
  );
}
