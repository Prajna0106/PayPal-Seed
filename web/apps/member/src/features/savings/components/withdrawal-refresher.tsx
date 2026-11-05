"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

const EVERY_MS = 8000;
const MAX_ROUNDS = 40; // about five minutes, then the member can reload

/**
 * While a withdrawal is still on its way, asks the server for the page again so the status changes without a reload.
 * It renders nothing: the list beside it is the live region that announces the change.
 */
export function WithdrawalRefresher({ active }: { active: boolean }) {
  const router = useRouter();

  useEffect(() => {
    if (!active) {
      return;
    }
    let rounds = 0;
    const timer = window.setInterval(() => {
      rounds += 1;
      if (rounds > MAX_ROUNDS) {
        window.clearInterval(timer);
        return;
      }
      if (document.visibilityState === "visible") {
        router.refresh();
      }
    }, EVERY_MS);
    return () => window.clearInterval(timer);
  }, [active, router]);

  return null;
}
