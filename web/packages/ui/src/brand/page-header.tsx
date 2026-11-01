import type { ReactNode } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

export interface PageHeaderProps {
  title: ReactNode;
  eyebrow?: ReactNode;
  description?: ReactNode;
  /** Buttons or menus aligned to the right on wide screens. */
  actions?: ReactNode;
  className?: string;
}

export function PageHeader({ title, eyebrow, description, actions, className }: PageHeaderProps) {
  return (
    <header className={cn("flex flex-col gap-4 md:flex-row md:items-end md:justify-between", className)}>
      <div className="flex max-w-3xl flex-col gap-2">
        {eyebrow ? <p className="text-eyebrow text-muted-ink">{eyebrow}</p> : null}
        <h1 className="text-display-lg text-ink text-balance">{title}</h1>
        {description ? <p className="text-body text-pretty">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  );
}
