import type { ReactNode } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";
import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";

export interface EmptyStateProps {
  title: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ title, description, icon, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        "relative isolate flex flex-col items-center gap-3 overflow-hidden rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft px-6 py-14 text-center",
        className,
      )}
    >
      <GradientOrb tone="lavender" blend="mint" drift={false} className="-top-24 left-1/2 -z-10 size-64 -translate-x-1/2 opacity-40" />
      {icon ? <span className="text-muted-ink [&_svg]:size-6">{icon}</span> : null}
      <h2 className="text-display-sm text-ink">{title}</h2>
      {description ? <p className="max-w-md text-sm text-body text-pretty">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}
