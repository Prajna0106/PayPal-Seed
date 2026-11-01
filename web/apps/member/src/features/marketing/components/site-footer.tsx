import { Wordmark } from "@paypal-seed/ui/brand/wordmark";
import Link from "next/link";
import type { ReactNode } from "react";
import { DESK_URL } from "../config";

interface FooterLink {
  label: string;
  href: string;
}

const COLUMNS: ReadonlyArray<{ title: string; links: ReadonlyArray<FooterLink> }> = [
  {
    title: "Product",
    links: [
      { label: "Who it is for", href: "#inclusion" },
      { label: "How it works", href: "#journey" },
      { label: "Safety net", href: "#safety-net" },
      { label: "For officers", href: "#officers" },
    ],
  },
  {
    title: "Trust",
    links: [
      { label: "Guardrails", href: "#trust" },
      { label: "Questions", href: "#faq" },
      { label: "Built with", href: "#built-with" },
    ],
  },
  {
    title: "Demo",
    links: [
      { label: "Member app", href: "/login" },
      { label: "Officer desk", href: DESK_URL },
    ],
  },
  {
    title: "PayPal",
    links: [
      { label: "PayPal Developer", href: "https://developer.paypal.com" },
      { label: "PayPal Sandbox", href: "https://developer.paypal.com/tools/sandbox/" },
    ],
  },
];

function FooterAnchor({ link }: { link: FooterLink }): ReactNode {
  const className = "text-[15px] text-body transition-colors hover:text-ink";
  if (link.href === "/login") {
    return (
      <Link href="/login" className={className}>
        {link.label}
      </Link>
    );
  }
  const external = link.href.startsWith("http");
  return (
    <a
      href={link.href}
      className={className}
      {...(external ? { target: "_blank", rel: "noopener noreferrer" } : {})}
    >
      {link.label}
      {external ? <span className="sr-only"> (opens in a new tab)</span> : null}
    </a>
  );
}

export function SiteFooter() {
  return (
    <footer className="border-t border-hairline bg-canvas">
      <div className="mx-auto grid max-w-6xl gap-10 px-6 py-16 sm:grid-cols-2 lg:grid-cols-5">
        <div className="sm:col-span-2 lg:col-span-1">
          <Wordmark />
          <p className="mt-4 max-w-xs text-[15px] leading-relaxed text-body">
            Savings and fair 0% microloans inside PayPal, with help that arrives before a payment is missed.
          </p>
        </div>
        {COLUMNS.map((column) => (
          <nav key={column.title} aria-label={column.title}>
            <h2 className="text-eyebrow text-muted-ink">{column.title}</h2>
            <ul className="mt-4 space-y-2.5">
              {column.links.map((link) => (
                <li key={link.label}>
                  <FooterAnchor link={link} />
                </li>
              ))}
            </ul>
          </nav>
        ))}
      </div>
      <div className="border-t border-hairline">
        <div className="mx-auto flex max-w-6xl flex-col gap-2 px-6 py-6 text-sm text-muted-ink sm:flex-row sm:justify-between">
          <p>Demo runs on the PayPal Sandbox; lending is offered with a licensed partner.</p>
          <p>Built for the PayPal AI Hackathon 2026.</p>
        </div>
      </div>
    </footer>
  );
}
