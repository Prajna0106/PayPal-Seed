import type { ReactNode } from "react";
import { MotionRoot } from "@/features/marketing/components/motion";
import { SiteFooter } from "@/features/marketing/components/site-footer";
import { SiteNav } from "@/features/marketing/components/site-nav";

export default function MarketingLayout({ children }: { children: ReactNode }) {
  return (
    <MotionRoot>
      <div className="relative isolate flex min-h-dvh flex-col">
        {/* The page frame: two hairlines on the content edges, behind everything. */}
        <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 hidden sm:block">
          <div className="mx-auto h-full max-w-6xl px-6">
            <div className="h-full border-x border-hairline/70" />
          </div>
        </div>
        <a
          href="#main"
          className="sr-only z-50 rounded-full bg-ink px-4 py-2 text-white focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
        >
          Skip to content
        </a>
        <SiteNav />
        <main id="main" className="flex-1">
          {children}
        </main>
        <SiteFooter />
      </div>
    </MotionRoot>
  );
}
