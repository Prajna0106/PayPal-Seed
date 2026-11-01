import { SeedMark } from "@paypal-seed/ui/brand/seed-mark";
import { cn } from "@paypal-seed/ui/lib/utils";

export interface WordmarkProps {
  className?: string;
  /** Hide the text and keep only the seed glyph (for collapsed sidebars). */
  compact?: boolean;
  /** Size the glyph, e.g. `size-8` in a taller header. */
  markClassName?: string;
}

export function Wordmark({ className, compact = false, markClassName }: WordmarkProps) {
  return (
    <span className={cn("inline-flex items-center gap-2 text-ink", className)}>
      <SeedMark className={markClassName} />
      {compact ? (
        <span className="sr-only">PayPal Seed</span>
      ) : (
        <span className="font-display text-xl leading-none font-light tracking-tight">
          PayPal <span className="italic">Seed</span>
        </span>
      )}
    </span>
  );
}
