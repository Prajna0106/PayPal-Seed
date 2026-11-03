"use client";

import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { PayPalButtons, PayPalScriptProvider } from "@paypal/react-paypal-js";
import { CheckCircle2 } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { createDonationIntent } from "../actions";

export interface DonateFormProps {
  clientId: string;
}

const PRESETS_CENTS = [500, 1000, 2500] as const;
// A sanity range for the custom box; PayPal and the backend still have the last word.
const MIN_CENTS = 100;
const MAX_CENTS = 100_000;

type Choice = (typeof PRESETS_CENTS)[number] | "custom";
type Outcome = { kind: "idle" } | { kind: "thanks"; amountCents: number } | { kind: "error"; message: string };

/** Whole or fractional dollars typed by the donor, as cents; `null` when it is not a usable amount. */
function parseDollars(text: string): number | null {
  const value = Number(text.trim().replace(/^\$/, ""));
  if (!Number.isFinite(value) || value <= 0) {
    return null;
  }
  const cents = Math.round(value * 100);
  return cents >= MIN_CENTS && cents <= MAX_CENTS ? cents : null;
}

/**
 * Donate to the safety fund with PayPal. The page creates a CAPTURE order whose `custom_id` came from our server, so
 * the capture webhook can tie the gift back to the donor's choice about their name. The backend records the gift
 * when PayPal's webhook arrives, not from this page.
 */
export function DonateForm({ clientId }: DonateFormProps) {
  const [choice, setChoice] = useState<Choice>(1000);
  const [custom, setCustom] = useState("");
  const [showName, setShowName] = useState(false);
  const [name, setName] = useState("");
  const [outcome, setOutcome] = useState<Outcome>({ kind: "idle" });
  const groupId = useId();

  const amountCents = choice === "custom" ? parseDollars(custom) : choice;

  // The PayPal callbacks are made once; they read the latest choices from here so the buttons are not rebuilt on every keystroke.
  const latest = useRef({ amountCents, showName, name });
  useEffect(() => {
    latest.current = { amountCents, showName, name };
  }, [amountCents, showName, name]);

  return (
    <div className="flex flex-col gap-6">
      <fieldset className="flex flex-col gap-3">
        <legend className="mb-1 text-sm font-medium text-ink">Choose an amount</legend>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {PRESETS_CENTS.map((cents) => (
            <AmountOption
              key={cents}
              id={`${groupId}-${cents}`}
              checked={choice === cents}
              onSelect={() => setChoice(cents)}
              label={formatMoney(cents)}
            />
          ))}
          <AmountOption id={`${groupId}-custom`} checked={choice === "custom"} onSelect={() => setChoice("custom")} label="Other" />
        </div>
        {choice === "custom" ? (
          <div className="flex flex-col gap-2 sm:max-w-xs">
            <Label htmlFor={`${groupId}-custom-amount`}>Amount in dollars</Label>
            <Input
              id={`${groupId}-custom-amount`}
              inputMode="decimal"
              autoComplete="off"
              placeholder="15"
              value={custom}
              onChange={(event) => setCustom(event.target.value)}
              aria-invalid={custom !== "" && amountCents === null}
              aria-describedby={`${groupId}-custom-hint`}
              className="h-11"
            />
            <p id={`${groupId}-custom-hint`} className="text-sm text-muted-ink">
              Between {formatMoney(MIN_CENTS)} and {formatMoney(MAX_CENTS)}.
            </p>
          </div>
        ) : null}
      </fieldset>

      <div className="flex flex-col gap-3">
        <label className="flex min-h-11 items-center gap-3 text-sm text-ink">
          <input
            type="checkbox"
            checked={showName}
            onChange={(event) => setShowName(event.target.checked)}
            className="size-4 accent-[var(--color-ink)]"
          />
          Show my first name on the supporters list
        </label>
        {showName ? (
          <div className="flex flex-col gap-2 sm:max-w-xs">
            <Label htmlFor={`${groupId}-name`}>Name to show</Label>
            <Input
              id={`${groupId}-name`}
              maxLength={40}
              autoComplete="given-name"
              value={name}
              onChange={(event) => setName(event.target.value)}
              className="h-11"
            />
          </div>
        ) : (
          <p className="text-sm text-muted-ink">Your gift is anonymous unless you choose to show a name.</p>
        )}
      </div>

      <div className="flex flex-col gap-3">
        <PayPalScriptProvider options={{ clientId, currency: "USD", intent: "capture", components: "buttons" }}>
          <PayPalButtons
            style={{ layout: "horizontal", shape: "pill", label: "donate", height: 44, tagline: false }}
            disabled={amountCents === null}
            createOrder={async (_data, actions) => {
              setOutcome({ kind: "idle" });
              const { amountCents: cents, showName: shown, name: typed } = latest.current;
              if (cents === null) {
                const message = "Please choose an amount first.";
                setOutcome({ kind: "error", message });
                throw new Error(message);
              }
              // The intent is made here, on the click, so a page view alone never creates a row.
              const intent = await createDonationIntent({ anonymous: !shown || typed.trim() === "", displayName: typed });
              if (!intent.ok) {
                setOutcome({ kind: "error", message: intent.message });
                throw new Error(intent.message);
              }
              return actions.order.create({
                intent: "CAPTURE",
                purchase_units: [
                  {
                    amount: { currency_code: "USD", value: (cents / 100).toFixed(2) },
                    custom_id: intent.customId,
                    description: "Seed safety fund",
                  },
                ],
              });
            }}
            onApprove={async (_data, actions) => {
              try {
                await actions.order?.capture();
                setOutcome({ kind: "thanks", amountCents: latest.current.amountCents ?? 0 });
              } catch {
                setOutcome({
                  kind: "error",
                  message: "PayPal could not finish the donation. If you were charged, it will still reach the fund.",
                });
              }
            }}
            onCancel={() => setOutcome({ kind: "idle" })}
            onError={() =>
              setOutcome((current) =>
                current.kind === "error" ? current : { kind: "error", message: "PayPal could not start the donation. Nothing was charged." },
              )
            }
          />
        </PayPalScriptProvider>

        <p role="status" aria-live="polite" className="min-h-5 text-sm">
          {outcome.kind === "thanks" ? (
            <span className="inline-flex items-start gap-2 text-success">
              <CheckCircle2 className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <span>
                Thank you{outcome.amountCents > 0 ? ` for ${formatMoney(outcome.amountCents)}` : ""}. It can take a moment for
                your gift to show up in the total above.
              </span>
            </span>
          ) : outcome.kind === "error" ? (
            <span className="text-danger">{outcome.message}</span>
          ) : null}
        </p>
      </div>
    </div>
  );
}

function AmountOption({ id, checked, onSelect, label }: { id: string; checked: boolean; onSelect: () => void; label: string }) {
  return (
    <label
      htmlFor={id}
      className="flex min-h-11 cursor-pointer items-center justify-center rounded-full border border-hairline bg-surface px-4 text-sm font-medium text-ink transition-colors has-[:checked]:border-ink has-[:checked]:bg-ink has-[:checked]:text-white has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50"
    >
      <input id={id} type="radio" name="amount" checked={checked} onChange={onSelect} className="sr-only" />
      {label}
    </label>
  );
}
