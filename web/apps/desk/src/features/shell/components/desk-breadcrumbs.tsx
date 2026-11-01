"use client";

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@paypal-seed/ui/components/breadcrumb";
import type { Route } from "next";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Fragment } from "react";

const LABELS: Record<string, string> = {
  hardship: "Hardship queue",
  loans: "Loans",
  agents: "Agents",
  operations: "Operations",
};

/** Ids are shortened (`9b2e…`) so a breadcrumb never wraps; the page header shows the full id. */
function label(segment: string): string {
  return LABELS[segment] ?? (segment.length > 12 ? `${segment.slice(0, 8)}…` : segment);
}

export function DeskBreadcrumbs() {
  const segments = usePathname().split("/").filter(Boolean);
  return (
    <Breadcrumb>
      <BreadcrumbList>
        <BreadcrumbItem>
          {segments.length === 0 ? (
            <BreadcrumbPage>Overview</BreadcrumbPage>
          ) : (
            <BreadcrumbLink asChild>
              <Link href="/">Overview</Link>
            </BreadcrumbLink>
          )}
        </BreadcrumbItem>
        {segments.map((segment, index) => {
          const href = `/${segments.slice(0, index + 1).join("/")}`;
          const last = index === segments.length - 1;
          // "loans" has no index page of its own; it is a label, not a link.
          const linkable = !last && segment !== "loans";
          return (
            <Fragment key={href}>
              <BreadcrumbSeparator />
              <BreadcrumbItem>
                {last ? (
                  <BreadcrumbPage>{label(segment)}</BreadcrumbPage>
                ) : linkable ? (
                  <BreadcrumbLink asChild>
                    <Link href={href as Route}>{label(segment)}</Link>
                  </BreadcrumbLink>
                ) : (
                  <span>{label(segment)}</span>
                )}
              </BreadcrumbItem>
            </Fragment>
          );
        })}
      </BreadcrumbList>
    </Breadcrumb>
  );
}
