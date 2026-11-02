"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { HeartHandshake } from "lucide-react";
import dynamic from "next/dynamic";

// The PayPal SDK is a browser script: load it only on the client, in a placeholder of about the same size.
const DonateForm = dynamic(() => import("./donate-form").then((m) => m.DonateForm), {
  ssr: false,
  loading: () => <Skeleton className="h-96 w-full rounded-2xl" aria-label="Loading the donation form" />,
});

/** Donate with PayPal, or a calm note when this environment has no PayPal client id. */
export function DonateSection({ clientId }: { clientId: string | undefined }) {
  if (!clientId) {
    return (
      <div className="flex items-start gap-3 rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft p-5">
        <HeartHandshake className="mt-0.5 size-5 shrink-0 text-muted-ink" aria-hidden="true" />
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium text-ink">Donations are not open here yet</p>
          <p className="text-sm text-body">Please check back soon. The fund figures above are always up to date.</p>
        </div>
      </div>
    );
  }
  return <DonateForm clientId={clientId} />;
}
