import { EmptyState } from "@paypal-seed/ui/brand/empty-state";
import { PageHeader } from "@paypal-seed/ui/brand/page-header";
import { Button } from "@paypal-seed/ui/components/button";
import { FileText } from "lucide-react";
import type { Metadata, Route } from "next";
import Link from "next/link";
import { ApplicationCard } from "@/features/lending/components/application-card";
import { listApplications } from "@/features/lending/server/queries";

export const metadata: Metadata = { title: "Your applications" };

export default async function ApplicationsPage() {
  const applications = await listApplications();
  const borrow = (
    <Button asChild className="h-11">
      <Link href={"/app/apply" as Route}>Borrow</Link>
    </Button>
  );

  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        eyebrow="Borrow"
        title="Your applications"
        description="Every loan application you have sent, and where each one is."
        actions={applications.length > 0 ? borrow : undefined}
      />
      {applications.length === 0 ? (
        <EmptyState
          icon={<FileText aria-hidden="true" />}
          title="No applications yet"
          description="When you apply for a Seed loan, you can follow it here, step by step."
          action={borrow}
        />
      ) : (
        <section aria-label="Your applications" className="grid gap-4 md:grid-cols-2">
          {applications.map((application) => (
            <ApplicationCard key={application.id} application={application} />
          ))}
        </section>
      )}
    </div>
  );
}
