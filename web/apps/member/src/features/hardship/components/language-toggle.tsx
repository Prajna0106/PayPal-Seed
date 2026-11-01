import type { Locale } from "@paypal-seed/i18n";
import { Languages } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { words } from "../copy";

/** Switches the offer between English and Spanish; the backend returns the message in the chosen language. */
export function LanguageToggle({ loanId, locale }: { loanId: string; locale: Locale }) {
  const other: Locale = locale === "es" ? "en" : "es";
  const href = (other === "es" ? `/app/loans/${loanId}/help?lang=es` : `/app/loans/${loanId}/help`) as Route;
  return (
    <Link
      href={href}
      hrefLang={other}
      lang={other}
      className="inline-flex h-9 items-center gap-2 rounded-full border border-hairline-strong px-4 text-sm font-medium text-ink transition-colors hover:bg-surface-strong"
    >
      <Languages className="size-4" aria-hidden="true" />
      {words(locale).language}
    </Link>
  );
}
