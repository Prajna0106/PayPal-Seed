import { cn } from "@paypal-seed/ui/lib/utils";
import { Coins, HeartHandshake, LockOpen, ShieldCheck, Sparkles, Sprout, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Float } from "./motion";

interface Chip {
  icon: LucideIcon;
  /** Where it sits around the card. */
  position: string;
  /** Pastel gradient behind the icon. */
  tone: string;
  size: "lg" | "md" | "sm";
  duration: number;
  delay: number;
  distance: number;
  className?: string;
}

const CHIPS: readonly Chip[] = [
  { icon: Sprout, position: "-top-7 -left-3 sm:-left-9", tone: "from-gradient-mint to-gradient-sky", size: "lg", duration: 7, delay: 0, distance: 10 },
  { icon: LockOpen, position: "-top-5 -right-2 sm:-right-8", tone: "from-gradient-lavender to-gradient-sky", size: "md", duration: 6, delay: 0.8, distance: 8 },
  { icon: ShieldCheck, position: "top-[46%] -left-5 sm:-left-14", tone: "from-gradient-sky to-gradient-mint", size: "md", duration: 8, delay: 1.4, distance: 9, className: "hidden sm:block" },
  { icon: Coins, position: "-bottom-7 right-8", tone: "from-gradient-peach to-gradient-rose", size: "lg", duration: 7.5, delay: 0.4, distance: 10 },
  { icon: HeartHandshake, position: "-bottom-5 left-10", tone: "from-gradient-rose to-gradient-lavender", size: "sm", duration: 5.5, delay: 1.1, distance: 7 },
  { icon: Sparkles, position: "top-[40%] -right-4 sm:-right-12", tone: "from-gradient-mint to-gradient-peach", size: "sm", duration: 6.5, delay: 1.9, distance: 8, className: "hidden sm:block" },
];

const SIZES = { lg: "size-14 rounded-2xl", md: "size-12 rounded-2xl", sm: "size-10 rounded-xl" } as const;
const ICONS = { lg: "size-6", md: "size-5", sm: "size-4" } as const;

/**
 * The hero's right side: the Seed card with a slow dashed ring behind it and a few glass icon chips drifting around
 * it. The chips carry no words; they are atmosphere, so the headline stays the only thing to read.
 */
export function HeroStage({ children }: { children: ReactNode }) {
  return (
    <div className="relative mx-auto w-full max-w-md lg:max-w-lg">
      <div aria-hidden="true" className="pointer-events-none absolute top-1/2 left-1/2 -z-10 size-[135%] -translate-x-1/2 -translate-y-1/2">
        <div className="size-full animate-spin rounded-full border border-dashed border-hairline-strong [animation-duration:70s] motion-reduce:animate-none">
          <span className="absolute top-[14%] left-[14%] size-2.5 rounded-full bg-gradient-mint shadow-soft" />
          <span className="absolute right-[10%] bottom-[22%] size-3 rounded-full bg-gradient-peach shadow-soft" />
          <span className="absolute top-[6%] right-[34%] size-2 rounded-full bg-gradient-lavender shadow-soft" />
        </div>
        <div className="absolute inset-[14%] rounded-full border border-hairline-soft" />
      </div>

      {children}

      {CHIPS.map(({ icon: Icon, position, tone, size, duration, delay, distance, className }) => (
        <div key={position} aria-hidden="true" className={cn("pointer-events-none absolute z-10", position, className)}>
          <Float duration={duration} delay={delay} distance={distance}>
            <div
              className={cn(
                "grid place-items-center border border-white/70 bg-linear-to-br shadow-lift backdrop-blur-md",
                SIZES[size],
                tone,
              )}
            >
              <Icon className={cn("text-ink", ICONS[size])} strokeWidth={1.75} />
            </div>
          </Float>
        </div>
      ))}
    </div>
  );
}
