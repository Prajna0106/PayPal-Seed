import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { DonateSection } from "@/features/fund/components/donate-section";
import { FundDashboard } from "@/features/fund/components/fund-dashboard";
import { getReserveSummary } from "@/features/fund/server/queries";

export const metadata: Metadata = {
  title: "The Seed safety fund",
  description: "A community fund that pays a member's loan in a hard month. See exactly what it holds and what it pays.",
};

const HOW_IT_WORKS = [
  { title: "A person raises a request", text: "When a member has a hard month and moving a date is not enough, a loan officer asks the fund to help." },
  { title: "Two officers must agree", text: "A second, different officer has to approve. The member never decides, and nobody can approve their own request." },
  { title: "The fund pays the loan", text: "The money goes to the member's loan, never to a person. It is small, capped and recorded in the books." },
] as const;

// The fund figures change with every donation and cover; a build-time snapshot would freeze whatever the backend
// answered (or failed to answer) while the site was being built.
export const dynamic = "force-dynamic";

export default async function FundPage() {
  const summary = await getReserveSummary();

  return (
    <div className="flex flex-col gap-12">
      <PageHeader
        eyebrow="Safety fund"
        title="A little help for a hard month"
        description="Seed lends at 0%. When a member has a bad month, this community fund can cover a payment on their loan. Everything it holds and pays is shown here."
      />

      <Section title="What the fund holds">
        {summary ? (
          <FundDashboard summary={summary} />
        ) : (
          <p role="status" className="rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft p-5 text-sm text-body">
            We cannot load the fund figures right now. Please try again in a few minutes.
          </p>
        )}
      </Section>

      <Section title="How the fund helps">
        <ol className="grid gap-4 sm:grid-cols-3">
          {HOW_IT_WORKS.map((step, index) => (
            <li key={step.title} className="flex flex-col gap-2 rounded-2xl border border-hairline bg-surface p-5">
              <span className="text-eyebrow text-muted-ink">Step {index + 1}</span>
              <h3 className="text-base font-medium text-ink">{step.title}</h3>
              <p className="text-sm text-body">{step.text}</p>
            </li>
          ))}
        </ol>
      </Section>

      <Section title="Give to the fund" description="Every gift stays in the fund until it covers a member's loan. We keep no email or address, and your gift is anonymous unless you choose to show a name.">
        <div className="rounded-2xl border border-hairline bg-surface p-6 shadow-soft">
          <DonateSection clientId={process.env.NEXT_PUBLIC_PAYPAL_CLIENT_ID} />
        </div>
      </Section>
    </div>
  );
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  const id = `section-${title.toLowerCase().replace(/\s+/g, "-")}`;
  return (
    <section aria-labelledby={id} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h2 id={id} className="text-display-sm text-ink">
          {title}
        </h2>
        {description ? <p className="max-w-prose text-sm text-body">{description}</p> : null}
      </div>
      {children}
    </section>
  );
}
