"use client";

import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { CreditCard } from "lucide-react";
import dynamic from "next/dynamic";
import type { PayNowProps } from "./pay-now";

// The PayPal SDK is a browser script: load it only on the client, in a placeholder of the same size.
const PayNow = dynamic(() => import("./pay-now").then((m) => m.PayNow), {
  ssr: false,
  loading: () => <Skeleton className="h-40 w-full rounded-2xl" aria-label="Loading PayPal" />,
});

/** "Pay now" with PayPal, or a plain explanation when this environment has no PayPal client id. */
export function PayNowSection(props: Omit<PayNowProps, "clientId"> & { clientId: string | undefined }) {
  const { clientId, ...rest } = props;
  if (!clientId) {
    return (
      <div className="flex items-start gap-3 rounded-2xl border border-dashed border-hairline-strong bg-canvas-soft p-5">
        <CreditCard className="mt-0.5 size-5 shrink-0 text-muted-ink" aria-hidden="true" />
        <div className="flex flex-col gap-1">
          <p className="text-sm font-medium text-ink">PayPal checkout is not set up here</p>
          <p className="text-sm text-body">
            Set <code className="rounded bg-surface-strong px-1 text-xs">NEXT_PUBLIC_PAYPAL_CLIENT_ID</code> to a PayPal
            sandbox client id to pay from this page. Autopay and payment links still work through PayPal.
          </p>
        </div>
      </div>
    );
  }
  return <PayNow clientId={clientId} {...rest} />;
}
