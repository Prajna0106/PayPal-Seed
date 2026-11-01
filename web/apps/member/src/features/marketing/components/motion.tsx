"use client";

import { cn } from "@paypal-seed/ui/lib/utils";
import { LazyMotion, MotionConfig, type Variants, domAnimation, m } from "framer-motion";
import type { ReactNode } from "react";

/**
 * The landing page's few motions, in one place: a fade-up on entering the viewport, a staggered group, a bar that
 * grows once, and a slow float. Reduced-motion visitors get the content without any movement (`reducedMotion="user"`).
 * Components use `m` inside `LazyMotion`, so only the small DOM animation bundle ships.
 */
const EASE: [number, number, number, number] = [0.22, 1, 0.36, 1];
const VIEWPORT = { once: true, margin: "-60px" } as const;

export function MotionRoot({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={domAnimation} strict>
      <MotionConfig reducedMotion="user">{children}</MotionConfig>
    </LazyMotion>
  );
}

interface RevealProps {
  children: ReactNode;
  className?: string;
  delay?: number;
}

export function Reveal({ children, className, delay = 0 }: RevealProps) {
  return (
    <m.div
      className={className}
      initial={{ opacity: 0, y: 18 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={VIEWPORT}
      transition={{ duration: 0.6, ease: EASE, delay }}
    >
      {children}
    </m.div>
  );
}

const GROUP: Variants = { hidden: {}, show: { transition: { staggerChildren: 0.08 } } };
const ITEM: Variants = {
  hidden: { opacity: 0, y: 16 },
  show: { opacity: 1, y: 0, transition: { duration: 0.55, ease: EASE } },
};

const GROUP_TAGS = { div: m.div, ul: m.ul, ol: m.ol, dl: m.dl } as const;
const ITEM_TAGS = { div: m.div, li: m.li } as const;

interface RevealGroupProps {
  as?: keyof typeof GROUP_TAGS;
  children: ReactNode;
  className?: string;
  "aria-label"?: string;
}

/** Children marked with `RevealItem` rise in one after another. */
export function RevealGroup({ as = "div", children, className, "aria-label": ariaLabel }: RevealGroupProps) {
  const Tag = GROUP_TAGS[as] as typeof m.div;
  return (
    <Tag
      className={className}
      aria-label={ariaLabel}
      variants={GROUP}
      initial="hidden"
      whileInView="show"
      viewport={VIEWPORT}
    >
      {children}
    </Tag>
  );
}

interface RevealItemProps {
  as?: keyof typeof ITEM_TAGS;
  children: ReactNode;
  className?: string;
}

export function RevealItem({ as = "div", children, className }: RevealItemProps) {
  const Tag = ITEM_TAGS[as] as typeof m.div;
  return (
    <Tag className={className} variants={ITEM}>
      {children}
    </Tag>
  );
}

interface GrowBarProps {
  className?: string;
  /** Final horizontal scale, 0 to 1. */
  to?: number;
  delay?: number;
}

/** A bar or rule that draws from the left once, when it scrolls into view. */
export function GrowBar({ className, to = 1, delay = 0 }: GrowBarProps) {
  return (
    <m.div
      aria-hidden="true"
      className={cn("origin-left", className)}
      initial={{ scaleX: 0 }}
      whileInView={{ scaleX: to }}
      viewport={VIEWPORT}
      transition={{ duration: 1.1, ease: EASE, delay }}
    />
  );
}

export function Float({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <m.div
      className={className}
      animate={{ y: [0, -6, 0] }}
      transition={{ duration: 6, ease: "easeInOut", repeat: Number.POSITIVE_INFINITY }}
    >
      {children}
    </m.div>
  );
}
