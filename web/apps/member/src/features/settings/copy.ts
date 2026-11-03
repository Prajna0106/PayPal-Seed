import type { ConsentTypeName } from "./schema";

/** What each agreement means, in plain words, and what happens when it is withdrawn. */
export const CONSENT_COPY: Record<ConsentTypeName, { title: string; about: string; onRevoke: string }> = {
  TERMS: {
    title: "Seed terms",
    about: "The rules for saving and borrowing with Seed.",
    onRevoke: "We record that you withdrew. You can agree again here whenever you like.",
  },
  PRIVACY: {
    title: "Privacy notice",
    about: "How Seed looks after your information.",
    onRevoke: "We record that you withdrew. You can agree again here whenever you like.",
  },
  INCOME_READ: {
    title: "Read my PayPal income",
    about: "Lets the fair loan check read your PayPal income, so a loan fits your real month.",
    onRevoke: "The fair check stops reading your income straight away, and we delete the income we already read.",
  },
  FAIR_CHECK: {
    title: "Fair loan check",
    about: "Lets Seed run the fair loan check when you apply, so any loan is sized to fit your real month.",
    onRevoke: "The fair check stops straight away, and we delete the income we already read. You can still save as usual.",
  },
  RECORD_SHARING: {
    title: "Share my Seed record",
    about: "Lets you make links that show your record to someone you choose.",
    onRevoke: "You cannot make new share links until you agree again.",
  },
};

/** `ana.perez@example.com` becomes `a***@example.com`: enough to recognise, not enough to read out. */
export function maskEmail(email: string | null | undefined): string {
  if (!email) {
    return "Not shared by PayPal";
  }
  const at = email.lastIndexOf("@");
  if (at < 1) {
    return "***";
  }
  return `${email.slice(0, 1)}***${email.slice(at)}`;
}
