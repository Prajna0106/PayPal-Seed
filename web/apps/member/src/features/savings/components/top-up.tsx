"use client";

import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { FUNDING, PayPalButtons, PayPalScriptProvider } from "@paypal/react-paypal-js";
import { CheckCircle2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState } from "react";
import { captureTopUp, startTopUp } from "../actions";
import { parseDollars, type Wallet } from "../model";

export interface TopUpProps {
  goalId: string;
  wallet: Wallet;
  clientId: string;
}

type Outcome = { kind: "idle" } | { kind: "added"; cents: number } | { kind: "error"; message: string };

/**
 * A one-off deposit. The backend creates and captures the order; the PayPal (or Venmo) button only collects the
 * member's approval. A goal saved from Venmo can only grow this way.
 */
export function TopUp({ goalId, wallet, clientId }: TopUpProps) {
  const router = useRouter();
  const id = useId();
  const [amount, setAmount] = useState("20");
  const [outcome, setOutcome] = useState<Outcome>({ kind: "idle" });
  const cents = parseDollars(amount);
  const valid = cents !== null && cents > 0;

  // The buttons are built once, so the click handler reads the amount through a ref to see what is typed now.
  const amountRef = useRef(amount);
  useEffect(() => {
    amountRef.current = amount;
  }, [amount]);

  return (
    <div className="flex flex-col gap-5">
      <div className="flex max-w-xs flex-col gap-2">
        <Label htmlFor={`${id}-amount`} className="text-ink">
          How much would you like to add?
        </Label>
        <div className="relative">
          <span aria-hidden="true" className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-ink">
            $
          </span>
          <Input
            id={`${id}-amount`}
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(event) => {
              setAmount(event.currentTarget.value);
              setOutcome({ kind: "idle" });
            }}
            aria-invalid={!valid && amount !== "" ? true : undefined}
            className="h-11 bg-surface pl-7"
          />
        </div>
      </div>

      <PayPalScriptProvider
        options={{
          clientId,
          currency: "USD",
          intent: "capture",
          components: "buttons",
          ...(wallet === "VENMO" ? { enableFunding: "venmo" } : {}),
        }}
      >
        <PayPalButtons
          fundingSource={wallet === "VENMO" ? FUNDING.VENMO : FUNDING.PAYPAL}
          disabled={!valid}
          style={{ layout: "horizontal", shape: "pill", height: 44, tagline: false }}
          createOrder={async () => {
            setOutcome({ kind: "idle" });
            const result = await startTopUp({ goalId, amount: amountRef.current });
            if (!result.ok) {
              setOutcome({ kind: "error", message: result.message });
              throw new Error(result.message);
            }
            return result.orderId;
          }}
          onApprove={async (data) => {
            const result = await captureTopUp({ goalId, orderId: data.orderID });
            if (result.ok) {
              setOutcome({ kind: "added", cents: parseDollars(amountRef.current) ?? 0 });
              router.refresh();
            } else {
              setOutcome({ kind: "error", message: result.message });
            }
          }}
          onCancel={() => setOutcome({ kind: "idle" })}
          onError={() =>
            setOutcome((current) =>
              current.kind === "error" ? current : { kind: "error", message: "PayPal could not finish the top-up. Please try again." },
            )
          }
        />
      </PayPalScriptProvider>

      <p role="status" aria-live="polite" className="min-h-5 text-sm">
        {outcome.kind === "added" ? (
          <span className="inline-flex items-start gap-2 text-success">
            <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
            Thank you. {formatMoney(outcome.cents)} is on its way into your goal. It shows in your balance as soon as PayPal confirms.
          </span>
        ) : outcome.kind === "error" ? (
          <span className="text-danger">{outcome.message}</span>
        ) : null}
      </p>
    </div>
  );
}
