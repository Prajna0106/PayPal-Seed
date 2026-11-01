import { cn } from "@paypal-seed/ui/lib/utils";

export interface SeedMarkProps {
  className?: string;
}

/**
 * The Seed mark: a sprout drawn in ink line on a soft sky-blue disc. Two leaves, a stem and a flat ground line, all
 * one stroke weight so it stays clear from a 16px favicon up to a hero.
 */
export function SeedMark({ className }: SeedMarkProps) {
  return (
    <svg viewBox="0 0 32 32" className={cn("size-7 shrink-0", className)} aria-hidden="true">
      <circle cx="16" cy="16" r="15.25" fill="var(--color-gradient-sky)" />
      <g fill="none" stroke="var(--color-ink)" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M10.5 24.2h11" />
        <path d="M16 24.2V15.8" />
        <path d="M16 15.6c.1-4 2.7-6.9 7.4-7.2.2 4-2.4 7-7.4 7.2z" />
        <path d="M16 18.4c-4.4.6-7.2-1.2-7.6-5 4.4-.6 7.4 1.2 7.6 5z" />
      </g>
    </svg>
  );
}
