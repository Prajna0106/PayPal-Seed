import { cn } from "@paypal-seed/ui/lib/utils";
import type { ReactNode } from "react";

export interface SectionProps {
  id?: string;
  eyebrow: string;
  title: ReactNode;
  intro?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Centre the heading block; body content keeps its own alignment. */
  centered?: boolean;
}

/** One editorial band: 96px rhythm, eyebrow, light serif heading, optional intro, then content. */
export function Section({ id, eyebrow, title, intro, children, className, centered = false }: SectionProps) {
  const headingId = id ? `${id}-title` : undefined;
  return (
    <section id={id} aria-labelledby={headingId} className={cn("scroll-mt-20 py-20 sm:py-24", className)}>
      <div className="mx-auto max-w-6xl px-6">
        <header className={cn("mb-12 max-w-2xl sm:mb-16", centered && "mx-auto text-center")}>
          <p className="text-eyebrow text-muted-ink">{eyebrow}</p>
          <h2 id={headingId} className="text-display-lg mt-3 text-balance text-ink">
            {title}
          </h2>
          {intro ? <p className="mt-4 text-base leading-relaxed text-pretty text-body">{intro}</p> : null}
        </header>
        {children}
      </div>
    </section>
  );
}
