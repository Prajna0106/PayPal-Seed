import type { Metadata } from "next";
import { BuiltWith } from "@/features/marketing/components/built-with";
import { CtaBand } from "@/features/marketing/components/cta-band";
import { Faq } from "@/features/marketing/components/faq";
import { Guardrails } from "@/features/marketing/components/guardrails";
import { Hero } from "@/features/marketing/components/hero";
import { Journey } from "@/features/marketing/components/journey";
import { OfficerGlimpse } from "@/features/marketing/components/officer-glimpse";
import { ProofStrip } from "@/features/marketing/components/proof-strip";
import { SafetyNetFlow } from "@/features/marketing/components/safety-net-flow";

const DESCRIPTION =
  "Savings and fair 0% microloans inside PayPal, with an AI safety net that offers help before a payment is missed. Numbers from code, words from AI, decisions from people.";

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000"),
  title: { absolute: "PayPal Seed — save a little, borrow fairly, get help early" },
  description: DESCRIPTION,
  openGraph: {
    title: "PayPal Seed",
    description: DESCRIPTION,
    type: "website",
    siteName: "PayPal Seed",
  },
  twitter: { card: "summary_large_image", title: "PayPal Seed", description: DESCRIPTION },
};

export default function LandingPage() {
  return (
    <>
      <Hero />
      <ProofStrip />
      <Journey />
      <SafetyNetFlow />
      <Guardrails />
      <OfficerGlimpse />
      <BuiltWith />
      <Faq />
      <CtaBand />
    </>
  );
}
