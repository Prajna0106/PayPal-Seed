import { Skeleton } from "@paypal-seed/ui/components/skeleton";
import { formatBusinessDate, formatMoney } from "@paypal-seed/ui/lib/format";
import { unstable_rethrow } from "next/navigation";
import { type PreviewNotice, previewNotice } from "../model";
import { getLoanPreview } from "../server/queries";

export function SchedulePreviewSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-busy="true" aria-label="Loading your payment calendar">
      <Skeleton className="h-5 w-48" />
      {Array.from({ length: 4 }, (_, index) => (
        <Skeleton key={index} className="h-10 w-full" />
      ))}
    </div>
  );
}

/**
 * Every payment with its date, from the backend (the same code that builds the real schedule when the money is sent).
 * Counted from today: sent on a later day, the dates move with it. If the backend cannot answer, the page says so and
 * the member can still send the application; nothing here is needed for it.
 */
export async function SchedulePreview({ amountCents, termMonths, dueDay }: { amountCents: number; termMonths: number; dueDay: number | null }) {
  let preview: Awaited<ReturnType<typeof getLoanPreview>>;
  try {
    preview = await getLoanPreview(amountCents, termMonths, dueDay);
  } catch (error) {
    unstable_rethrow(error);
    console.error("[apply] schedule preview failed", error);
    return (
      <p className="text-sm text-muted-ink text-pretty">
        We could not draw your payment calendar just now. Your full calendar, with every date, appears on your plan page as soon as the
        money is sent.
      </p>
    );
  }
  const notice: PreviewNotice | null = previewNotice(preview);
  if (notice) {
    return <p className="text-sm text-muted-ink text-pretty">{notice.message} We check this again when you send it.</p>;
  }
  return (
    <div className="flex flex-col gap-3">
      <table className="w-full text-left text-body">
        <caption className="pb-3 text-left text-title-sm font-medium text-ink">Your payment calendar</caption>
        <thead className="text-sm text-muted-ink">
          <tr className="border-b border-hairline">
            <th scope="col" className="py-2 pr-4 font-normal">
              Payment
            </th>
            <th scope="col" className="py-2 pr-4 font-normal">
              Due on
            </th>
            <th scope="col" className="py-2 text-right font-normal">
              Amount
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-hairline">
          {preview.instalments.map((instalment) => (
            <tr key={instalment.seq}>
              <th scope="row" className="py-3 pr-4 font-normal tabular-nums">
                {instalment.seq}
              </th>
              <td className="py-3 pr-4">{formatBusinessDate(instalment.dueDate)}</td>
              <td className="py-3 text-right font-medium text-ink tabular-nums">{formatMoney(instalment.amountCents)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t border-hairline">
            <th scope="row" colSpan={2} className="py-3 pr-4 font-medium text-ink">
              Total you repay
            </th>
            <td className="py-3 text-right font-medium text-ink tabular-nums">{formatMoney(preview.totalRepayableCents)}</td>
          </tr>
        </tfoot>
      </table>
      <p className="text-sm text-muted-ink text-pretty">
        Dates are counted from today. If the money is sent on a later day, they move with it. Payments are whole dollars, so one or two
        may differ by $1.
      </p>
    </div>
  );
}
