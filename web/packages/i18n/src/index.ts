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
  "hardship.aiLabel": "The amounts below come from your loan record, and this note was checked against them.",
  "hardship.askTitle": "Ask for help with a payment",
  "hardship.askReason": "Anything you want to tell us (optional)",
  "hardship.askReasonHint": "We only use this to sort your request. It is never shared.",
  "home.activityTitle": "Recent activity",
  "home.activityEmpty": "Nothing yet. Your first deposit will show up here.",
  "home.monthsToGo": "{count} more months of on-time saving to unlock the next step.",
  "savings.nextDeposit": "Next deposit",
  "savings.changeAmount": "Change monthly amount",
  "savings.changeAmountHint": "The new amount starts with your next deposit. You can change it again any time.",
  "savings.withdrawalOnItsWay": "On its way",
  "savings.withdrawalSent": "Sent",
  "savings.checkWithPaypal": "Check with PayPal",
  "fund.chooseAmount": "Choose an amount",
  "fund.amountRange": "Any amount from $1 to $1,000.",
  "fund.thanks": "Thank you. It can take a moment to show in the total.",
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
  "hardship.aiLabel": "Las cantidades de abajo salen de tu préstamo, y esta nota se comprobó con ellas.",
  "hardship.askTitle": "Pide ayuda con un pago",
  "hardship.askReason": "Algo que quieras contarnos (opcional)",
  "hardship.askReasonHint": "Solo lo usamos para ordenar tu solicitud. Nunca se comparte.",
  "home.activityTitle": "Actividad reciente",
  "home.activityEmpty": "Aún nada. Tu primer depósito aparecerá aquí.",
  "home.monthsToGo": "Faltan {count} meses ahorrando a tiempo para el siguiente paso.",
  "savings.nextDeposit": "Próximo depósito",
  "savings.changeAmount": "Cambiar el monto mensual",
  "savings.changeAmountHint": "El nuevo monto empieza con tu próximo depósito. Puedes cambiarlo otra vez cuando quieras.",
  "savings.withdrawalOnItsWay": "En camino",
  "savings.withdrawalSent": "Enviado",
  "savings.checkWithPaypal": "Consultar con PayPal",
  "fund.chooseAmount": "Elige un monto",
  "fund.amountRange": "Cualquier monto de $1 a $1,000.",
  "fund.thanks": "Gracias. Puede tardar un momento en verse en el total.",
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
