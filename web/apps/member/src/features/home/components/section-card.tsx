import { cn } from "@paypal-seed/ui/lib/utils";
import { ArrowRight } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { SECTION_LABEL } from "../copy";
import type { HomeSectionErrorCode, HomeSectionName } from "../model";

/** One hairline card on Home: an eyebrow with an icon, the content, and one link out. */
export function SectionCard({
  title,
  icon,
  href,
  linkLabel,
  children,
  className,
}: {
  title: string;
  icon: ReactNode;
  href?: string;
  linkLabel?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section aria-label={title} className={cn("flex h-full flex-col gap-5 rounded-2xl border border-hairline bg-surface p-6", className)}>
      <header className="flex items-center gap-2 text-muted-ink [&_svg]:size-4">
        {icon}
        <h2 className="text-eyebrow">{title}</h2>
      </header>
      <div className="flex flex-1 flex-col gap-4">{children}</div>
      {href && linkLabel ? (
        <Link
          href={href as Route}
          className="inline-flex min-h-11 items-center gap-1 self-start rounded-full text-sm font-medium text-ink outline-none hover:underline focus-visible:ring-[3px] focus-visible:ring-ring/50"
        >
          {linkLabel}
          <ArrowRight aria-hidden="true" className="size-4" />
        </Link>
      ) : null}
    </section>
  );
}

/**
 * A section the backend could not build. Calm and local: the rest of the page stays. `no-data` is not an error, so
 * callers handle it with their own empty state and use this only for `unavailable`.
 */
export function SectionUnavailable({ section, title, icon }: { section: HomeSectionName; title: string; icon: ReactNode }) {
  return (
    <SectionCard title={title} icon={icon}>
      <p role="status" className="text-sm text-body text-pretty">
        We could not load {SECTION_LABEL[section]} right now. The rest of your page is fine.
      </p>
      <Link
        href={"/app" as Route}
        className="inline-flex min-h-11 items-center self-start rounded-full text-sm font-medium text-ink underline underline-offset-4 outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
      >
        Try again
      </Link>
    </SectionCard>
  );
}

/** The same message as a slim line, for a section that is a banner rather than a card (payment help). */
export function SectionNote({ section }: { section: HomeSectionName }) {
  return (
    <p className="text-sm text-muted-ink text-pretty">
      We could not load {SECTION_LABEL[section]} right now.{" "}
      <Link href={"/app" as Route} className="font-medium text-ink underline underline-offset-4">
        Try again
      </Link>
    </p>
  );
}

export function isUnavailable(code: HomeSectionErrorCode | null): boolean {
  return code === "unavailable";
}
