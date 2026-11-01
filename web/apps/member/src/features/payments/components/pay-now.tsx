"use client";

import { Label } from "@paypal-seed/ui/components/label";
import { RadioGroup, RadioGroupItem } from "@paypal-seed/ui/components/radio-group";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { PayPalButtons, PayPalScriptProvider } from "@paypal/react-paypal-js";
import { CheckCircle2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { capturePaymentOrder, createPaymentOrder, type PayChoice } from "../actions";

export interface PayNowProps {
  loanId: string;
  clientId: string;
  nextCents: number | null;
  payoffCents: number;
}

type Outcome = { kind: "idle" } | { kind: "paid"; amountCents: number } | { kind: "error"; message: string };

/**
 * PayPal checkout for the next payment or the whole balance. The backend creates and captures the order; the
 * PayPal buttons only collect the member's approval.
 */
export function PayNow({ loanId, clientId, nextCents, payoffCents }: PayNowProps) {
  const router = useRouter();
  const groupId = useId();
  const [choice, setChoice] = useState<PayChoice>(nextCents ? "next" : "payoff");
  const [outcome, setOutcome] = useState<Outcome>({ kind: "idle" });

  return (
    <div className="flex flex-col gap-5">
      <RadioGroup
        value={choice}
        onValueChange={(value) => setChoice(value === "payoff" ? "payoff" : "next")}
        aria-label="What to pay"
        className="grid gap-3 sm:grid-cols-2"
      >
        {nextCents ? (
          <ChoiceTile id={`${groupId}-next`} value="next" title="Next payment" amount={formatMoney(nextCents)} />
        ) : null}
        <ChoiceTile id={`${groupId}-payoff`} value="payoff" title="Pay off the loan" amount={formatMoney(payoffCents)} />
      </RadioGroup>

      <PayPalScriptProvider options={{ clientId, currency: "USD", intent: "capture", components: "buttons" }}>
        <PayPalButtons
          // A new choice means a new order, so the buttons are rebuilt with the right amount.
          key={choice}
          style={{ layout: "horizontal", shape: "pill", label: "pay", height: 44, tagline: false }}
          createOrder={async () => {
            setOutcome({ kind: "idle" });
            const result = await createPaymentOrder({ loanId, choice });
            if (!result.ok) {
              setOutcome({ kind: "error", message: result.message });
              throw new Error(result.message);
            }
            return result.orderId;
          }}
          onApprove={async (data) => {
            const result = await capturePaymentOrder({ loanId, orderId: data.orderID });
            if (result.ok) {
              setOutcome({ kind: "paid", amountCents: result.amountCents });
              router.refresh();
            } else {
              setOutcome({ kind: "error", message: result.message });
            }
          }}
          onCancel={() => setOutcome({ kind: "idle" })}
          onError={() =>
            setOutcome((current) =>
              current.kind === "error" ? current : { kind: "error", message: "PayPal could not finish the payment. Please try again." },
            )
          }
        />
      </PayPalScriptProvider>

      <p role="status" aria-live="polite" className="min-h-5 text-sm">
        {outcome.kind === "paid" ? (
          <span className="inline-flex items-center gap-2 text-success">
            <CheckCircle2 className="size-4" aria-hidden="true" />
            Thank you. {formatMoney(outcome.amountCents)} is on its way to your loan.
          </span>
        ) : outcome.kind === "error" ? (
          <span className="text-danger">{outcome.message}</span>
        ) : null}
      </p>
    </div>
  );
}

function ChoiceTile({ id, value, title, amount }: { id: string; value: PayChoice; title: string; amount: string }) {
  return (
    <Label
      htmlFor={id}
      className="flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-hairline bg-surface p-4 has-[[data-state=checked]]:border-ink"
    >
      <span className="flex items-center gap-3">
        <RadioGroupItem id={id} value={value} />
        <span className="text-sm font-medium text-ink">{title}</span>
      </span>
      <span className="text-base font-medium text-ink tabular-nums">{amount}</span>
    </Label>
  );
}
