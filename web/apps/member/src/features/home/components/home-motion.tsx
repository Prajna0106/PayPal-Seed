"use client";

import { LazyMotion, domAnimation, m, useReducedMotion } from "framer-motion";
import type { ReactNode } from "react";

/**
 * Home's two small motions: a card that fades up once, and a progress bar that draws from the left. People who ask
 * for reduced motion get the same content with no movement at all (plain elements, no animation).
 */
const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];

/** Provides the small DOM animation bundle to the `m` components below. */
export function HomeMotion({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={domAnimation} strict>
      {children}
    </LazyMotion>
  );
}

export function FadeUp({ children, className, delay = 0 }: { children: ReactNode; className?: string; delay?: number }) {
  const reduce = useReducedMotion();
  if (reduce) {
    return <div className={className}>{children}</div>;
  }
  return (
    <m.div
      className={className}
      initial={{ opacity: 0, y: 14 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, ease: EASE, delay }}
    >
      {children}
    </m.div>
  );
}

/** `percent` is for drawing only (0 to 100); the labels beside it carry the real numbers from the backend. */
export function DrawBar({ percent, label }: { percent: number; label: string }) {
  const reduce = useReducedMotion();
  const clamped = Math.min(100, Math.max(0, percent));
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(clamped)}
      className="h-1.5 w-full overflow-hidden rounded-full bg-surface-strong"
    >
      {reduce ? (
        <div className="h-full rounded-full bg-ink" style={{ width: `${clamped}%` }} />
      ) : (
        <m.div
          className="h-full origin-left rounded-full bg-ink"
          style={{ width: `${clamped}%` }}
          initial={{ scaleX: 0 }}
          animate={{ scaleX: 1 }}
          transition={{ duration: 0.9, ease: EASE, delay: 0.25 }}
        />
      )}
    </div>
  );
}
