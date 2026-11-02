import type { Metadata } from "next";
import { HomeView } from "@/features/home/components/home-view";
import { getHome } from "@/features/home/server/queries";
import { requireSession } from "@/server/session";

export const metadata: Metadata = { title: "Home" };

export default async function MemberHomePage() {
  const [session, home] = await Promise.all([requireSession(), getHome()]);
  return <HomeView home={home} fallbackName={session.displayName} />;
}
