"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { RefreshCw } from "lucide-react";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { syncGoal } from "../actions";

/** Asks the backend to read this goal from PayPal now, for when PayPal's own message is late or never arrives. */
export function SyncButton({ goalId }: { goalId: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="min-h-11"
      disabled={pending}
      aria-busy={pending}
      onClick={() =>
        startTransition(async () => {
          await syncGoal(goalId);
          router.refresh();
        })
      }
    >
      <RefreshCw className={pending ? "animate-spin motion-reduce:animate-none" : undefined} aria-hidden="true" />
      {pending ? "Checking with PayPal" : "Check with PayPal"}
    </Button>
  );
}
