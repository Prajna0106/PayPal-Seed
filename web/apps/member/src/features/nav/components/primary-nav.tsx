"use client";

import { cn } from "@paypal-seed/ui/lib/utils";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { isActive, NAV_ITEMS } from "../items";

/** The wide-screen navigation: one pill per route, the current one filled. */
export function PrimaryNav() {
  const pathname = usePathname();
  return (
    <nav aria-label="Primary" className="hidden lg:block">
      <ul className="flex items-center gap-1">
        {NAV_ITEMS.map((item) => {
          const current = isActive(item, pathname);
          return (
            <li key={item.href}>
              <Link
                href={item.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "inline-flex h-10 items-center rounded-full px-3.5 text-sm font-medium transition-colors outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50",
                  current ? "bg-surface-strong text-ink" : "text-body hover:bg-surface-strong/60 hover:text-ink",
                )}
              >
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
