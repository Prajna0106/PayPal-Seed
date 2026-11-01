import { cn } from "@paypal-seed/ui/lib/utils";
import type { ReactNode } from "react";
import { Reveal } from "./motion";

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

/** A small crosshair where a section rule meets the page frame. */
function Cross({ className }: { className: string }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 11 11" className={cn("absolute hidden size-[11px] text-hairline-strong sm:block", className)}>
      <path d="M5.5 0v11M0 5.5h11" stroke="currentColor" strokeWidth="1" fill="none" />
    </svg>
  );
}

/**
 * One editorial band: a hairline rule with crosshairs on the page frame, eyebrow, light serif heading, optional
 * intro, then content.
 */
export function Section({ id, eyebrow, title, intro, children, className, centered = false }: SectionProps) {
  const headingId = id ? `${id}-title` : undefined;
  return (
    <section id={id} aria-labelledby={headingId} className={cn("relative scroll-mt-20 py-16 sm:py-24", className)}>
      <div aria-hidden="true" className="absolute inset-x-0 top-0 h-px bg-hairline" />
      <div className="mx-auto max-w-6xl px-6">
        <div className="relative">
          <Cross className="-top-[5px] -left-[5px]" />
          <Cross className="-top-[5px] -right-[5px]" />
        </div>
        <Reveal>
          <header className={cn("mb-12 max-w-2xl sm:mb-16", centered && "mx-auto text-center")}>
            <p className="text-eyebrow text-muted-ink">{eyebrow}</p>
            <h2 id={headingId} className="text-display-lg mt-3 text-balance text-ink">
              {title}
            </h2>
            {intro ? <p className="mt-4 text-base leading-relaxed text-pretty text-body">{intro}</p> : null}
          </header>
        </Reveal>
        {children}
      </div>
    </section>
  );
}
