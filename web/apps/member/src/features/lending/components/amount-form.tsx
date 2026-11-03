"use client";

import { Button } from "@paypal-seed/ui/components/button";
import { Input } from "@paypal-seed/ui/components/input";
import { Label } from "@paypal-seed/ui/components/label";
import { formatMoney } from "@paypal-seed/ui/lib/format";
import { ArrowRight, Minus, Plus } from "lucide-react";
import { useId, useState } from "react";
import { PURPOSES } from "../model";
import { LivePreview } from "./live-preview";

export interface AmountFormProps {
  /** Whole dollars. All limits come from the backend's eligibility answer. */
  minDollars: number;
  maxDollars: number;
  minTerm: number;
  maxTerm: number;
  maxMonthlyCents: number;
  initialAmount: number;
  initialTerm: number;
  initialPurpose: string;
  initialDueDay: number | null;
  suggestedDueDay: number | null;
}

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

/**
 * Step 2. Slider and number box move together, clamped to the limits. It submits as a plain GET to the next step, so
 * the choice lives in the URL. The monthly payment is not worked out here: the live preview asks the backend for it.
 */
export function AmountForm(props: AmountFormProps) {
  const id = useId();
  const [amount, setAmount] = useState(clamp(props.initialAmount, props.minDollars, props.maxDollars));
  const [amountText, setAmountText] = useState(String(amount));
  const [term, setTerm] = useState(clamp(props.initialTerm, props.minTerm, props.maxTerm));
  const [purpose, setPurpose] = useState(props.initialPurpose);
  const [dueDayText, setDueDayText] = useState(props.initialDueDay === null && props.suggestedDueDay === null ? "" : String(props.initialDueDay ?? props.suggestedDueDay));
  // Only a day the backend would accept is sent to the preview; anything else lets it use its default.
  const dueDayValue = Number(dueDayText);
  const previewDueDay = Number.isInteger(dueDayValue) && dueDayValue >= 1 && dueDayValue <= 28 ? dueDayValue : null;

  function commitAmount(value: number) {
    const next = clamp(Math.round(Number.isFinite(value) ? value : props.minDollars), props.minDollars, props.maxDollars);
    setAmount(next);
    setAmountText(String(next));
  }

  return (
    <form method="get" action="/app/apply" className="flex flex-col gap-8">
      <input type="hidden" name="step" value="consent" />

      <div className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <Label htmlFor={`${id}-amount`} className="text-base text-ink">
          How much would you like to borrow?
        </Label>
        <div className="flex items-center gap-2">
          <span aria-hidden="true" className="text-display-sm text-ink">
            $
          </span>
          <Input
            id={`${id}-amount`}
            name="amount"
            type="number"
            inputMode="numeric"
            min={props.minDollars}
            max={props.maxDollars}
            step={1}
            required
            value={amountText}
            aria-describedby={`${id}-amount-hint`}
            onChange={(event) => setAmountText(event.currentTarget.value)}
            onBlur={(event) => commitAmount(event.currentTarget.valueAsNumber)}
            className="h-12 max-w-40 text-xl tabular-nums md:text-xl"
          />
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-11"
            aria-label="Borrow $10 less"
            onClick={() => commitAmount(amount - 10)}
          >
            <Minus aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-11"
            aria-label="Borrow $10 more"
            onClick={() => commitAmount(amount + 10)}
          >
            <Plus aria-hidden="true" />
          </Button>
        </div>
        <input
          type="range"
          aria-label="Loan amount"
          min={props.minDollars}
          max={props.maxDollars}
          step={5}
          value={amount}
          onChange={(event) => commitAmount(event.currentTarget.valueAsNumber)}
          className="h-11 w-full cursor-pointer accent-ink motion-reduce:transition-none"
        />
        <p id={`${id}-amount-hint`} className="text-sm text-muted-ink">
          Between {formatMoney(props.minDollars * 100)} and {formatMoney(props.maxDollars * 100)}, in whole dollars.
        </p>
      </div>

      <div className="flex flex-col gap-3 rounded-2xl border border-hairline bg-surface p-6">
        <Label htmlFor={`${id}-term`} className="text-base text-ink">
          Over how many months?
        </Label>
        <div className="flex items-center gap-2">
          <Input
            id={`${id}-term`}
            name="term"
            type="number"
            inputMode="numeric"
            min={props.minTerm}
            max={props.maxTerm}
            step={1}
            required
            value={term}
            aria-describedby={`${id}-term-hint`}
            onChange={(event) => {
              const next = event.currentTarget.valueAsNumber;
              if (Number.isFinite(next)) setTerm(clamp(Math.round(next), props.minTerm, props.maxTerm));
            }}
            className="h-12 max-w-28 text-xl tabular-nums md:text-xl"
          />
          <span className="text-body">months</span>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-11"
            aria-label="One month fewer"
            disabled={term <= props.minTerm}
            onClick={() => setTerm((value) => clamp(value - 1, props.minTerm, props.maxTerm))}
          >
            <Minus aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon"
            className="size-11"
            aria-label="One month more"
            disabled={term >= props.maxTerm}
            onClick={() => setTerm((value) => clamp(value + 1, props.minTerm, props.maxTerm))}
          >
            <Plus aria-hidden="true" />
          </Button>
        </div>
        <p id={`${id}-term-hint`} className="text-sm text-muted-ink">
          {props.minTerm} to {props.maxTerm} months. Payments stay at or under {formatMoney(props.maxMonthlyCents)} a month.
        </p>
      </div>

      <LivePreview
        amountDollars={amount}
        term={term}
        dueDay={previewDueDay}
        onTry={(suggestion) => {
          commitAmount(suggestion.amountDollars);
          setTerm(clamp(suggestion.term, props.minTerm, props.maxTerm));
        }}
      />

      <div className="grid gap-6 sm:grid-cols-2">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-purpose`} className="text-ink">
            What is it for?
          </Label>
          <select
            id={`${id}-purpose`}
            name="purpose"
            required
            value={purpose}
            onChange={(event) => setPurpose(event.currentTarget.value)}
            className="h-11 w-full rounded-md border border-input bg-surface px-3 text-base outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <option value="" disabled>
              Choose one
            </option>
            {PURPOSES.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-due`} className="text-ink">
            Payment day of the month (optional)
          </Label>
          <Input
            id={`${id}-due`}
            name="dueDay"
            type="number"
            inputMode="numeric"
            min={1}
            max={28}
            value={dueDayText}
            onChange={(event) => setDueDayText(event.currentTarget.value)}
            aria-describedby={`${id}-due-hint`}
            className="h-11"
          />
          <p id={`${id}-due-hint`} className="text-sm text-muted-ink">
            {props.suggestedDueDay
              ? `Day ${props.suggestedDueDay} suits you: it is the day after your money usually arrives.`
              : "A day from 1 to 28. Pick the day after your money usually arrives."}
          </p>
        </div>
      </div>

      <p className="text-sm text-muted-ink">Seed loans cost 0% and have no fees. That is the whole price.</p>

      <Button type="submit" size="lg" className="h-12 self-start" disabled={purpose === ""}>
        Continue
        <ArrowRight aria-hidden="true" />
      </Button>
    </form>
  );
}
