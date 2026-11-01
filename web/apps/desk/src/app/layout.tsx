import { Toaster } from "@paypal-seed/ui/components/sonner";
import { TooltipProvider } from "@paypal-seed/ui/components/tooltip";
import type { Metadata, Viewport } from "next";
import { Inter, Newsreader } from "next/font/google";
import type { ReactNode } from "react";
import "./globals.css";

const newsreader = Newsreader({
  subsets: ["latin"],
  weight: ["300", "400"],
  style: ["normal", "italic"],
  variable: "--font-newsreader",
  display: "swap",
});

const inter = Inter({ subsets: ["latin"], variable: "--font-inter", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Seed Desk", template: "%s · Seed Desk" },
  description: "Loan officer desk for PayPal Seed: hardship queue, approvals, agents and operations.",
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: "#fafafa",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" className={`${newsreader.variable} ${inter.variable}`}>
      <body className="min-h-dvh">
        <TooltipProvider delayDuration={200}>{children}</TooltipProvider>
        <Toaster position="bottom-right" />
      </body>
    </html>
  );
}
