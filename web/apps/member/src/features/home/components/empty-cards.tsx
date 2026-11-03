import { Button } from "@paypal-seed/ui/components/button";
import { PiggyBank, ScrollText } from "lucide-react";
import type { Route } from "next";
import Link from "next/link";
import { SectionCard } from "./section-card";

/** Savings for a member who has loan activity but is not saving yet. */
export function SavingsEmpty() {
  return (
    <SectionCard title="Savings" icon={<PiggyBank aria-hidden="true" />}>
      <p className="font-display text-2xl font-light text-ink">Start with $5 a month</p>
      <p className="text-sm text-body text-pretty">Saving steadily builds your Seed record and keeps a fair 0% loan within reach.</p>
      <Button asChild variant="outline" className="mt-auto h-11 self-start">
        <Link href={"/app/savings/new" as Route}>Start saving</Link>
      </Button>
    </SectionCard>
  );
}

/** The record has nothing to show until there is a first deposit or payment. */
export function RecordEmpty() {
  return (
    <SectionCard title="Your Seed record" icon={<ScrollText aria-hidden="true" />} href="/app/record" linkLabel="See your record">
      <p className="font-display text-2xl font-light text-ink">Your record starts here</p>
      <p className="text-sm text-body text-pretty">Each on-time deposit and payment adds to it. It is yours to keep and share.</p>
    </SectionCard>
  );
}
