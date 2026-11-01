import type { CSSProperties } from "react";
import { cn } from "@paypal-seed/ui/lib/utils";

export type OrbTone = "mint" | "peach" | "lavender" | "sky" | "rose";

export interface GradientOrbProps {
  tone?: OrbTone;
  /** A second tone blended into the edge of the orb. */
  blend?: OrbTone;
  className?: string;
  style?: CSSProperties;
  /** Slow drift; switched off automatically under prefers-reduced-motion. */
  drift?: boolean;
}

/**
 * Atmospheric decoration only (DESIGN.md): a soft radial bloom that never holds content, never fills a button and
 * never colours text. Position it with `className` inside a `relative overflow-hidden` parent.
 */
export function GradientOrb({ tone = "mint", blend = "sky", className, style, drift = true }: GradientOrbProps) {
  return (
    <div
      aria-hidden="true"
      className={cn(
        "pointer-events-none absolute rounded-full opacity-80 blur-3xl",
        drift && "motion-safe:animate-orb-drift",
        className,
      )}
      style={{
        background: `radial-gradient(circle at 35% 35%, var(--color-gradient-${tone}) 0%, var(--color-gradient-${blend}) 45%, transparent 70%)`,
        ...style,
      }}
    />
  );
}
