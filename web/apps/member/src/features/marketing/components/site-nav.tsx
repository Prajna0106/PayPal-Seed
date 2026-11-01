import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import { Button } from "@paypal-seed/ui/components/button";
import { Menu } from "lucide-react";
import Link from "next/link";
import { DESK_URL } from "../config";
import { NAV_LINKS } from "../content";

/**
 * Top navigation. The small-screen menu is a native disclosure, so the whole landing page ships without client
 * JavaScript of its own.
 */
export function SiteNav() {
  return (
    <header className="sticky top-0 z-40 border-b border-hairline/70 bg-canvas/80 backdrop-blur-md supports-[backdrop-filter]:bg-canvas/70">
      <nav aria-label="Main" className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-6 px-6">
        <Link href="/" aria-label="PayPal Seed home" className="rounded-full">
          <Wordmark />
        </Link>

        <ul className="hidden items-center gap-1 lg:flex">
          {NAV_LINKS.map((link) => (
            <li key={link.href}>
              <a
                href={link.href}
                className="rounded-full px-3 py-2 text-[15px] font-medium text-body transition-colors hover:text-ink"
              >
                {link.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="flex items-center gap-2">
          <a
            href={DESK_URL}
            className="hidden rounded-full px-3 py-2 text-[15px] font-medium text-body transition-colors hover:text-ink sm:inline-flex"
          >
            Officer desk
          </a>
          <Button asChild>
            <Link href="/login">Open the demo</Link>
          </Button>

          <details className="group relative lg:hidden">
            <summary
              aria-label="Menu"
              className="inline-flex size-10 cursor-pointer list-none items-center justify-center rounded-full border border-hairline-strong text-ink [&::-webkit-details-marker]:hidden"
            >
              <Menu aria-hidden="true" className="size-4" />
            </summary>
            <div className="absolute right-0 mt-2 w-60 rounded-2xl border border-hairline bg-surface p-2 shadow-lift">
              <ul>
                {NAV_LINKS.map((link) => (
                  <li key={link.href}>
                    <a
                      href={link.href}
                      className="flex min-h-11 items-center rounded-xl px-3 text-[15px] text-ink hover:bg-surface-strong"
                    >
                      {link.label}
                    </a>
                  </li>
                ))}
                <li className="mt-1 border-t border-hairline-soft pt-1">
                  <a
                    href={DESK_URL}
                    className="flex min-h-11 items-center rounded-xl px-3 text-[15px] text-ink hover:bg-surface-strong"
                  >
                    Officer desk
                  </a>
                </li>
              </ul>
            </div>
          </details>
        </div>
      </nav>
    </header>
  );
}
