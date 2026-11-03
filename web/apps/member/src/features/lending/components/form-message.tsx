import { CheckCircle2, CircleAlert } from "lucide-react";
import type { FormState } from "../actions";

/**
 * The result of a form, in a live region so a screen reader hears it. The region is always in the page; only its
 * content changes, which is what makes `aria-live` announce.
 */
export function FormMessage({ state, relinkHref }: { state: FormState; relinkHref?: string }) {
  return (
    <div aria-live="polite" className="empty:hidden">
      {state.status === "error" ? (
        <p className="flex items-start gap-2 rounded-lg bg-danger-soft px-3 py-2.5 text-sm text-danger">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>
            {state.message}
            {state.relink && relinkHref ? (
              <>
                {" "}
                {/* A plain link on purpose: this starts a PayPal login, and a prefetch must not. */}
                <a href={relinkHref} className="font-medium underline underline-offset-4">
                  Sign in with PayPal again
                </a>
              </>
            ) : null}
          </span>
        </p>
      ) : null}
      {state.status === "ok" ? (
        <p className="flex items-start gap-2 rounded-lg bg-success-soft px-3 py-2.5 text-sm text-success">
          <CheckCircle2 aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>{state.message}</span>
        </p>
      ) : null}
    </div>
  );
}
