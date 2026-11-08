import { cn } from "@paypal-seed/ui/lib/utils";

export interface SeedMarkProps {
  className?: string;
}

/**
 * The Seed mark: two leaves rising from a small seed, on a light rounded tile. Filled shapes in deeper pastel
 * gradients, so it keeps its contrast on the off-white page and stays clear from a 16px favicon up to a hero.
 * Each stop has a plain token colour and a darker `color-mix` on top; a browser without `color-mix` keeps the token.
 * The gradient ids repeat on every instance and every definition is identical, so several marks on one page are fine.
 */
export function SeedMark({ className }: SeedMarkProps) {
  return (
    <svg viewBox="0 0 32 32" className={cn("size-7 shrink-0", className)} aria-hidden="true">
      <defs>
        <linearGradient id="seed-mark-leaf" x1="14" y1="24" x2="26" y2="7" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="var(--color-success)" />
          <stop offset="1" stopColor="var(--color-gradient-mint)" style={{ stopColor: "color-mix(in srgb, var(--color-gradient-mint) 70%, var(--color-success))" }} />
        </linearGradient>
        <linearGradient id="seed-mark-leaf-small" x1="16" y1="24" x2="7" y2="12" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="var(--color-gradient-sky)" style={{ stopColor: "color-mix(in srgb, var(--color-gradient-sky) 70%, var(--color-ink))" }} />
          <stop offset="1" stopColor="var(--color-gradient-lavender)" style={{ stopColor: "color-mix(in srgb, var(--color-gradient-lavender) 80%, var(--color-ink))" }} />
        </linearGradient>
      </defs>
      <rect x="0.5" y="0.5" width="31" height="31" rx="9" fill="var(--color-surface)" stroke="var(--color-hairline-strong)" />
      <path d="M16.6 22.6C16 14.4 19.2 9 25.6 7.4c.7 6.6-2.6 12.6-9 15.2z" fill="url(#seed-mark-leaf)" />
      <path d="M15.4 22.6C15.5 17.4 12.6 13.8 7.2 12.9c-.4 5 2.6 8.9 8.2 9.7z" fill="url(#seed-mark-leaf-small)" />
      <circle cx="16" cy="25.6" r="1.9" fill="var(--color-gradient-peach)" style={{ fill: "color-mix(in srgb, var(--color-gradient-peach) 75%, var(--color-ink))" }} />
    </svg>
  );
}
