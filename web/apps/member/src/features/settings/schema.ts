import { z } from "zod";

export const profileSchema = z.object({
  displayName: z.string().trim().min(1, "Please enter the name you would like us to use.").max(120, "Please keep your name under 120 characters."),
  language: z.enum(["en", "es"], { error: "Please choose English or Spanish." }),
  walletPreference: z.enum(["PAYPAL", "VENMO"], { error: "Please choose PayPal or Venmo." }),
});

export const CONSENT_TYPES = ["TERMS", "PRIVACY", "INCOME_READ", "FAIR_CHECK", "RECORD_SHARING"] as const;
export type ConsentTypeName = (typeof CONSENT_TYPES)[number];

export const consentChangeSchema = z.object({
  type: z.enum(CONSENT_TYPES),
  intent: z.enum(["grant", "revoke"]),
});
