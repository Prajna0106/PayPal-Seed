/**
 * A tiny typed dictionary for the member-facing en/es strings. English is the source of truth for the keys; Spanish
 * must provide every key (the type checker enforces it). Values may contain `{name}` placeholders.
 */

export const LOCALES = ["en", "es"] as const;
export type Locale = (typeof LOCALES)[number];

const en = {
  "common.noFees": "No fees on any option.",
  "common.youChoose": "You choose, and you can say no.",
  "hardship.title": "A little help with this month",
  "hardship.suggested": "Suggested for you",
  "hardship.choose": "Choose this option",
  "hardship.decline": "No thanks",
  "hardship.reviewing": "Our team is reviewing your choice. We will reply by {when}.",
  "hardship.applied": "Your new plan is ready.",
  "hardship.aiLabel": "Written by Seed's assistant and checked against your numbers.",
  "hardship.askTitle": "Ask for help with a payment",
  "hardship.askReason": "Anything you want to tell us (optional)",
  "hardship.askReasonHint": "We only use this to sort your request. It is never shared.",
} as const;

export type MessageKey = keyof typeof en;
type Dictionary = Record<MessageKey, string>;

const es: Dictionary = {
  "common.noFees": "Ninguna opción tiene cargos.",
  "common.youChoose": "Tú decides y puedes decir que no.",
  "hardship.title": "Un poco de ayuda con este mes",
  "hardship.suggested": "Sugerida para ti",
  "hardship.choose": "Elegir esta opción",
  "hardship.decline": "No, gracias",
  "hardship.reviewing": "Nuestro equipo está revisando tu elección. Te responderemos antes del {when}.",
  "hardship.applied": "Tu nuevo plan está listo.",
  "hardship.aiLabel": "Escrito por el asistente de Seed y comprobado con tus números.",
  "hardship.askTitle": "Pide ayuda con un pago",
  "hardship.askReason": "Algo que quieras contarnos (opcional)",
  "hardship.askReasonHint": "Solo lo usamos para ordenar tu solicitud. Nunca se comparte.",
};

const DICTIONARIES: Record<Locale, Dictionary> = { en, es };

export function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && (LOCALES as readonly string[]).includes(value);
}

/** Returns a translator bound to one locale: `t("hardship.reviewing", { when: "Friday" })`. */
export function translator(locale: Locale) {
  const dictionary = DICTIONARIES[locale];
  return (key: MessageKey, values?: Record<string, string | number>): string => {
    const template = dictionary[key];
    return values ? template.replace(/\{(\w+)\}/g, (match, name: string) => String(values[name] ?? match)) : template;
  };
}
