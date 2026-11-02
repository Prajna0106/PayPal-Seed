import { GradientOrb } from "@paypal-seed/ui/brand/gradient-orb";
import { Button } from "@paypal-seed/ui/components/button";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { CheckCircle2 } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";
import { getApplication } from "@/features/lending/server/queries";

export const metadata: Metadata = { title: "Application sent" };

export default async function SubmittedPage({ searchParams }: PageProps<"/app/apply/submitted">) {
  const { application } = await searchParams;
  const id = z.uuid().safeParse(Array.isArray(application) ? application[0] : application);
  const found = id.success ? await getApplication(id.data) : null;
  if (!found) {
    notFound();
  }

  return (
    <div className="relative isolate flex max-w-2xl flex-col gap-6 overflow-hidden rounded-3xl border border-hairline bg-canvas-soft p-8 sm:p-12">
      <GradientOrb tone="mint" blend="sky" className="-top-24 -right-16 -z-10 size-72 opacity-60" />
      <CheckCircle2 aria-hidden="true" className="size-8 text-success" />
      <div className="flex flex-col gap-3">
        <h1 className="text-display-lg text-ink text-balance">
          Your application is in
        </h1>
        <p className="text-body text-pretty">
          Thank you. You asked for {formatMoney(found.amountCents)} over {found.termMonths} months. A person at Seed will read it, and we
          will tell you as soon as there is news.
        </p>
        <p className="text-body text-pretty">
          Nothing is owed yet. If you change your mind, you can return the money within 48 hours of receiving it, at no cost.
        </p>
      </div>
      <div className="flex flex-col gap-3 sm:flex-row">
        <Button asChild size="lg" className="h-12">
          <Link href={`/app/applications/${found.id}` as Route}>See your application</Link>
        </Button>
        <Button asChild size="lg" variant="outline" className="h-12">
          <Link href="/app">Back to home</Link>
        </Button>
      </div>
    </div>
  );
}
