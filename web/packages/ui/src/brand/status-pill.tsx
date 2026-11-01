import type { ReactNode } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

export type StatusTone = "neutral" | "success" | "warning" | "danger" | "info" | "ink";

const TONES: Record<StatusTone, string> = {
  neutral: "bg-surface-strong text-ink-soft [--dot:var(--color-muted-ink)]",
  success: "bg-success-soft text-success [--dot:var(--color-success)]",
  warning: "bg-warning-soft text-warning [--dot:var(--color-warning)]",
  danger: "bg-danger-soft text-danger [--dot:var(--color-danger)]",
  info: "bg-info-soft text-info [--dot:var(--color-info)]",
  ink: "bg-ink text-white [--dot:var(--color-gradient-mint)]",
};

export interface StatusPillProps {
  tone?: StatusTone;
  children: ReactNode;
  /** Leading dot; off for dense table cells. */
  dot?: boolean;
  className?: string;
}

/** Quiet semantic status: colour supports the label, never replaces it. */
export function StatusPill({ tone = "neutral", children, dot = true, className }: StatusPillProps) {
  return (
    <span
      className={cn(
        "inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-medium whitespace-nowrap",
        TONES[tone],
        className,
      )}
    >
      {dot ? <span aria-hidden="true" className="size-1.5 rounded-full bg-[var(--dot)]" /> : null}
      {children}
    </span>
  );
}
