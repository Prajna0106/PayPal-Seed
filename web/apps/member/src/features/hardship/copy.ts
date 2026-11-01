import { type Locale, translator } from "@paypal-seed/i18n";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";

/**
 * Member-facing words for the safety net, in English and Spanish. Every number comes from the option's summary
 * (computed by the backend); these functions only put it in a sentence. No risk words, no pressure.
 */

export type OptionLabel = "A" | "B" | "C" | "D";
export type Summary = Record<string, number | string>;

const DATE_LOCALE: Record<Locale, string> = { en: "en-US", es: "es-US" };

/** Option summaries carry dates as ISO strings, or as epoch days on older backends; both read the same. */
export function summaryDate(summary: Summary, key: string): string | null {
  const value = summary[key];
  if (typeof value === "string") {
    return value;
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return new Date(value * 86_400_000).toISOString().slice(0, 10);
  }
  return null;
}

function summaryNumber(summary: Summary, key: string): number | null {
  const value = summary[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

const WORDS = {
  en: {
    titles: { A: "Move the date", B: "Pause one month", C: "Smaller, longer plan", D: "Pay what you can" },
    tiers: {
      AUTO_POLICY: "Takes effect as soon as you choose it.",
      MAKER_CHECKER: "A person on our team reviews it, usually within a business day.",
      NONE: "You pay with PayPal. Nothing else changes.",
    },
    warnings: {
      NOT_SUSTAINABLE_AT_CURRENT_INCOME: "At your recent income, this may still be hard to keep up with.",
      STILL_TIGHT: "Even this plan may feel tight for a while.",
      LEAVES_SHORTFALL: "This covers part of the payment, not all of it.",
    } as Record<string, string>,
    choose: "Choose this option",
    chooseConfirmTitle: "Choose “{title}”?",
    chooseConfirmBody: "We'll use exactly the numbers shown here. You can't change it afterwards, but there are no fees.",
    confirm: "Yes, choose it",
    cancel: "Not now",
    decline: "No thanks, keep my plan",
    declined: "Got it. Your plan stays exactly as it is.",
    message: "A note from Seed",
    aiLabel: translator("en")("hardship.aiLabel"),
    noFees: translator("en")("common.noFees"),
    youChoose: translator("en")("common.youChoose"),
    title: translator("en")("hardship.title"),
    target: "Your {amount} payment due {date}",
    expires: "These options are open until {date}.",
    stale: "The numbers changed since this offer was made (for example, a payment arrived). Please look at the updated options and choose again.",
    reviewing: "Our team is reviewing your choice. We'll reply by {when}.",
    reviewingNoDate: "Our team is reviewing your choice. We'll let you know as soon as it's done.",
    approved: "Your choice was approved. Your new plan is being put in place.",
    applied: translator("en")("hardship.applied"),
    payToFinish: "To finish, pay {amount} with PayPal from your plan page.",
    executionDelayed: "Your choice was approved. Updating your plan is taking a little longer than usual; nothing more is needed from you.",
    viewPlan: "See your plan",
    language: "Español",
  },
  es: {
    titles: { A: "Cambiar la fecha", B: "Pausar un mes", C: "Plan más largo y pagos más pequeños", D: "Paga lo que puedas" },
    tiers: {
      AUTO_POLICY: "Se aplica en cuanto la eliges.",
      MAKER_CHECKER: "Una persona de nuestro equipo la revisa, normalmente en un día hábil.",
      NONE: "Pagas con PayPal. Nada más cambia.",
    },
    warnings: {
      NOT_SUSTAINABLE_AT_CURRENT_INCOME: "Con tus ingresos recientes, puede seguir siendo difícil de pagar.",
      STILL_TIGHT: "Incluso este plan puede ser ajustado por un tiempo.",
      LEAVES_SHORTFALL: "Cubre una parte del pago, no todo.",
    } as Record<string, string>,
    choose: "Elegir esta opción",
    chooseConfirmTitle: "¿Elegir «{title}»?",
    chooseConfirmBody: "Usaremos exactamente los números que ves aquí. No podrás cambiarlo después, pero no hay cargos.",
    confirm: "Sí, elegirla",
    cancel: "Ahora no",
    decline: "No, gracias, mantener mi plan",
    declined: "Entendido. Tu plan sigue igual.",
    message: "Una nota de Seed",
    aiLabel: translator("es")("hardship.aiLabel"),
    noFees: translator("es")("common.noFees"),
    youChoose: translator("es")("common.youChoose"),
    title: translator("es")("hardship.title"),
    target: "Tu pago de {amount} que vence el {date}",
    expires: "Estas opciones están disponibles hasta el {date}.",
    stale: "Los números cambiaron desde que hicimos esta oferta (por ejemplo, llegó un pago). Revisa las opciones actualizadas y elige de nuevo.",
    reviewing: "Nuestro equipo está revisando tu elección. Te responderemos antes del {when}.",
    reviewingNoDate: "Nuestro equipo está revisando tu elección. Te avisaremos en cuanto termine.",
    approved: "Tu elección fue aprobada. Estamos preparando tu nuevo plan.",
    applied: translator("es")("hardship.applied"),
    payToFinish: "Para terminar, paga {amount} con PayPal desde la página de tu plan.",
    executionDelayed: "Tu elección fue aprobada. Actualizar tu plan está tardando un poco más de lo normal; no necesitas hacer nada.",
    viewPlan: "Ver tu plan",
    language: "English",
  },
} as const;

export type Words = (typeof WORDS)["en"];

export function words(locale: Locale): Words {
  return WORDS[locale] as Words;
}

export function fill(template: string, values: Record<string, string>): string {
  return template.replace(/\{(\w+)\}/g, (match, name: string) => values[name] ?? match);
}

export function money(cents: number | null): string {
  return cents === null ? "—" : formatMoney(cents);
}

export function date(iso: string | null, locale: Locale): string {
  return iso ? formatBusinessDate(iso, "long", DATE_LOCALE[locale]) : "—";
}

/** One sentence that says what the option does to the member's payments, in their language. */
export function describeOption(label: OptionLabel, summary: Summary, locale: Locale): string {
  const n = (key: string) => summaryNumber(summary, key);
  const d = (key: string) => date(summaryDate(summary, key), locale);
  const es = locale === "es";
  switch (label) {
    case "A":
      return es
        ? `Paga ${money(n("amountCents"))} el ${d("movedToDate")} en lugar del ${d("movedFromDate")}. Tus demás pagos no cambian.`
        : `Pay ${money(n("amountCents"))} on ${d("movedToDate")} instead of ${d("movedFromDate")}. Your other payments stay the same.`;
    case "B":
      return es
        ? `Sáltate el pago de ${money(n("amountCents"))} del ${d("removedFromDate")}. Pasa al final de tu plan, el ${d("addedOnDate")}.`
        : `Skip the ${money(n("amountCents"))} payment due ${d("removedFromDate")}. It moves to the end of your plan, on ${d("addedOnDate")}.`;
    case "C": {
      const min = n("minInstalmentCents");
      const max = n("maxInstalmentCents");
      const range = min !== null && max !== null && min !== max ? `${money(min)}–${money(max)}` : money(max);
      const extra = n("extraMonths") ?? 0;
      return es
        ? `Paga ${range} al mes durante ${extra} ${extra === 1 ? "mes" : "meses"} más. Tu último pago sería el ${d("lastDueDate")}.`
        : `Pay ${range} a month, over ${extra} more ${extra === 1 ? "month" : "months"}. Your last payment would be ${d("lastDueDate")}.`;
    }
    case "D":
      return es
        ? `Paga ${money(n("payNowCents"))} ahora. Los otros ${money(n("stillDueCents"))} siguen venciendo el ${d("stillDueOnDate")}.`
        : `Pay ${money(n("payNowCents"))} now. The other ${money(n("stillDueCents"))} is still due on ${d("stillDueOnDate")}.`;
  }
}
