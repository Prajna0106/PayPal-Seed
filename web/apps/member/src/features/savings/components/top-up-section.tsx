"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { CreditCard } from "lucide-react";
import dynamic from "next/dynamic";
import type { TopUpProps } from "./top-up";

// The PayPal SDK is a browser script: load it only on the client, in a placeholder of the same size.
const TopUp = dynamic(() => import("./top-up").then((m) => m.TopUp), {
  ssr: false,
  loading: () => <Skeleton className="h-44 w-full max-w-md rounded-2xl" aria-label="Loading PayPal" />,
});

/** "Add money" with PayPal or Venmo, or a plain explanation when this environment has no PayPal client id. */
export function TopUpSection(props: Omit<TopUpProps, "clientId"> & { clientId: string | undefined }) {
  const { clientId, ...rest } = props;
  if (!clientId) {
    return (
      <div className="flex items-start gap-3 rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft p-5">
        <CreditCard className="mt-0.5 size-5 shrink-0 text-muted-ink" aria-hidden="true" />
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium text-ink">Adding money is not set up here</p>
          <p className="text-sm text-body">
            Set <code className="rounded bg-surface-strong px-1 text-xs">NEXT_PUBLIC_PAYPAL_CLIENT_ID</code> to a PayPal
            sandbox client id to add money from this page.
          </p>
        </div>
      </div>
    );
  }
  return <TopUp clientId={clientId} {...rest} />;
}
