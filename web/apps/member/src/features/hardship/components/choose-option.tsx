"use client";

import type { Locale } from "@paypal-seed/i18n";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@paypal-seed/ui/components/alert-dialog";
import { Button } from "@paypal-seed/ui/components/button";
import { Loader2 } from "lucide-react";
import { usePathname, useRouter } from "next/navigation";
import { useActionState, useEffect, useState } from "react";
import { chooseOption, type ChooseState } from "../actions";
import { fill, words } from "../copy";

export interface ChooseOptionProps {
  loanId: string;
  caseId: string;
  offerId: string;
  optionId: string;
  attemptKey: string;
  title: string;
  description: string;
  recommended: boolean;
  locale: Locale;
}

/**
 * The member's choice, confirmed in a dialog that repeats the exact sentence they are agreeing to. The result is
 * carried in the URL so the page that replaces the offer can show it, even after a reload.
 */
export function ChooseOption({ loanId, caseId, offerId, optionId, attemptKey, title, description, recommended, locale }: ChooseOptionProps) {
  const t = words(locale);
  const router = useRouter();
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [state, action, pending] = useActionState<ChooseState, FormData>(chooseOption, { status: "idle" });

  useEffect(() => {
    if (state.status === "chosen") {
      const params = new URLSearchParams({ chosen: state.optionLabel || "1", status: state.caseStatus });
      if (state.decisionDueAt) {
        params.set("due", state.decisionDueAt);
      }
      if (state.payNowCents) {
        params.set("pay", String(state.payNowCents));
      }
      if (locale === "es") {
        params.set("lang", "es");
      }
      router.replace(`${pathname}?${params.toString()}` as Parameters<typeof router.replace>[0]);
    } else if (state.status === "stale") {
      const params = new URLSearchParams({ changed: "1", ...(locale === "es" ? { lang: "es" } : {}) });
      router.replace(`${pathname}?${params.toString()}` as Parameters<typeof router.replace>[0]);
    }
  }, [state, pathname, router, locale]);

  return (
    <AlertDialog open={open} onOpenChange={(next) => !pending && setOpen(next)}>
      <AlertDialogTrigger asChild>
        <Button variant={recommended ? "default" : "outline"} className="w-full">
          {t.choose}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <form action={action} className="flex flex-col gap-4">
          <input type="hidden" name="loanId" value={loanId} />
          <input type="hidden" name="caseId" value={caseId} />
          <input type="hidden" name="offerId" value={offerId} />
          <input type="hidden" name="optionId" value={optionId} />
          <input type="hidden" name="attemptKey" value={attemptKey} />
          <AlertDialogHeader>
            <AlertDialogTitle className="font-display text-2xl font-light">{fill(t.chooseConfirmTitle, { title })}</AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="flex flex-col gap-3 text-body">
                <p className="text-ink">{description}</p>
                <p>{t.chooseConfirmBody}</p>
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          {state.status === "error" ? (
            <p role="alert" className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger">
              {state.message}
            </p>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogCancel type="button" disabled={pending}>
              {t.cancel}
            </AlertDialogCancel>
            <Button type="submit" disabled={pending} aria-busy={pending}>
              {pending ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
              {t.confirm}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
