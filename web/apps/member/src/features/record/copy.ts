import type { Locale } from "@paypal-seed/i18n";

export interface TrustWords {
  title: string;
  description: string;
  explanationHeading: string;
  attestationHeading: string;
  attestationHint: string;
  adviceHeading: string;
  figuresLabel: string;
  onTime: string;
  streak: string;
  saved: string;
  repaid: string;
  limitNow: string;
  limitNext: string;
  verification: string;
  issued: string;
  aiLabel: string;
  language: string;
  loading: string;
}

const WORDS: Record<Locale, TrustWords> = {
  en: {
    title: "Your record, in plain words",
    description: "What your record says today, and the wording another lender would read if you choose to share it.",
    explanationHeading: "What this means for you",
    attestationHeading: "Certificate wording",
    attestationHint: "This is the text that goes on your record certificate. It is written in English.",
    adviceHeading: "A small next step",
    figuresLabel: "The numbers behind it",
    onTime: "On-time repayments",
    streak: "Months in a row",
    saved: "Saved so far",
    repaid: "Repaid so far",
    limitNow: "Your limit now",
    limitNext: "At the next step",
    verification: "Verification code",
    issued: "Issued",
    aiLabel: "Written by Seed's assistant and checked against your numbers.",
    language: "Español",
    loading: "Loading your record in words",
  },
  es: {
    title: "Tu historial, en palabras claras",
    description: "Lo que dice tu historial hoy y el texto que leería otro prestamista si decides compartirlo.",
    explanationHeading: "Qué significa para ti",
    attestationHeading: "Texto del certificado",
    attestationHint: "Este es el texto de tu certificado de historial. Está escrito en inglés.",
    adviceHeading: "Un pequeño siguiente paso",
    figuresLabel: "Los números detrás",
    onTime: "Pagos puntuales",
    streak: "Meses seguidos",
    saved: "Ahorrado hasta ahora",
    repaid: "Pagado hasta ahora",
    limitNow: "Tu límite ahora",
    limitNext: "En el siguiente paso",
    verification: "Código de verificación",
    issued: "Emitido",
    aiLabel: "Escrito por el asistente de Seed y comprobado con tus números.",
    language: "English",
    loading: "Cargando tu historial en palabras",
  },
};

export function trustWords(locale: Locale): TrustWords {
  return WORDS[locale];
}
