import { useId } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

export interface SeedMarkProps {
  className?: string;
}

/**
 * The Seed mark: a pastel orb (the same gradient as the page atmosphere) holding an ink seed that is already
 * sprouting. The sprout is cut out of the seed in canvas colour, so it reads on light and dark surfaces.
 */
export function SeedMark({ className }: SeedMarkProps) {
  const id = useId();
  return (
    <svg viewBox="0 0 32 32" className={cn("size-7 shrink-0", className)} aria-hidden="true">
      <defs>
        <radialGradient id={`${id}-orb`} cx="30%" cy="24%" r="95%">
          <stop offset="0%" stopColor="var(--color-gradient-mint)" />
          <stop offset="50%" stopColor="var(--color-gradient-sky)" />
          <stop offset="100%" stopColor="var(--color-gradient-lavender)" />
        </radialGradient>
      </defs>
      <circle cx="16" cy="16" r="15.25" fill={`url(#${id}-orb)`} />
      <circle cx="16" cy="16" r="15.25" fill="none" stroke="var(--color-ink)" strokeOpacity="0.1" strokeWidth="0.75" />
      <path
        d="M16 6.2c1.9 3.1 5.4 5.6 6.6 9.5 1.3 4.2-1.1 8.9-6.6 8.9s-7.9-4.7-6.6-8.9C10.6 11.8 14.1 9.3 16 6.2z"
        fill="var(--color-ink)"
      />
      <path d="M16 23V16.4" stroke="var(--color-canvas)" strokeWidth="1.5" strokeLinecap="round" fill="none" />
      <path d="M16 17.2c0-2.4 1.7-3.9 4-3.9 0 2.5-1.5 3.9-4 3.9z" fill="var(--color-canvas)" />
      <path d="M16 19.2c0-1.7-1.2-2.8-2.9-2.8 0 1.8 1.1 2.8 2.9 2.8z" fill="var(--color-canvas)" />
    </svg>
  );
}
