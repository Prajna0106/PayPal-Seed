/**
 * Display formatting only. Every number comes from the backend: money as integer cents, business dates as plain
 * `YYYY-MM-DD` strings, rates as basis points. Nothing here computes a business value.
 */

const LOCALE = "en-US";

const wholeDollars = new Intl.NumberFormat(LOCALE, {
  style: "currency",
  currency: "USD",
  maximumFractionDigits: 0,
});
const withCents = new Intl.NumberFormat(LOCALE, { style: "currency", currency: "USD", minimumFractionDigits: 2 });
const compact = new Intl.NumberFormat(LOCALE, {
  style: "currency",
  currency: "USD",
  notation: "compact",
  maximumFractionDigits: 1,
});

export interface MoneyFormat {
  /** `$1.2K` style, for dense dashboards. */
  compact?: boolean;
  /** Always show cents, even for whole-dollar amounts. */
  showCents?: boolean;
}

/** `15000` → `$150`; `41142` → `$411.42`. Whole-dollar amounts drop the cents unless asked. */
export function formatMoney(cents: number, options: MoneyFormat = {}): string {
  if (!Number.isFinite(cents)) {
    return "—";
  }
  const dollars = cents / 100;
  if (options.compact) {
    return compact.format(dollars);
  }
  return !options.showCents && cents % 100 === 0 ? wholeDollars.format(dollars) : withCents.format(dollars);
}

export type BusinessDateStyle = "long" | "medium" | "short" | "weekday" | "month";

const DATE_STYLES: Record<BusinessDateStyle, Intl.DateTimeFormatOptions> = {
  long: { year: "numeric", month: "long", day: "numeric" },
  medium: { year: "numeric", month: "short", day: "numeric" },
  short: { month: "short", day: "numeric" },
  weekday: { weekday: "long", month: "long", day: "numeric" },
  month: { year: "numeric", month: "long" },
};

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Business dates are calendar dates with no time zone. They are read as UTC midnight and formatted in UTC, so
 * `2026-11-20` is November 20 everywhere, never November 19 in a western time zone.
 */
export function parseBusinessDate(iso: string): Date {
  const match = ISO_DATE.exec(iso);
  if (!match) {
    throw new RangeError(`Not a business date: ${iso}`);
  }
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
}

export function formatBusinessDate(iso: string, style: BusinessDateStyle = "medium", locale = LOCALE): string {
  return new Intl.DateTimeFormat(locale, { ...DATE_STYLES[style], timeZone: "UTC" }).format(parseBusinessDate(iso));
}

/** Instants (timestamps with a zone) shown in the program's time zone unless another is given. */
export function formatDateTime(
  iso: string,
  options: { timeZone?: string; dateStyle?: "medium" | "short"; timeStyle?: "short" } = {},
): string {
  return new Intl.DateTimeFormat(LOCALE, {
    dateStyle: options.dateStyle ?? "medium",
    timeStyle: options.timeStyle ?? "short",
    timeZone: options.timeZone ?? "America/Los_Angeles",
  }).format(new Date(iso));
}

/** `5200` → `52%`; `8228` → `82.28%`. */
export function formatPercentBps(bps: number): string {
  const percent = bps / 100;
  return `${Number.isInteger(percent) ? percent : percent.toFixed(2)}%`;
}

const relative = new Intl.RelativeTimeFormat(LOCALE, { numeric: "auto" });
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

/** `in 3 hours`, `2 days ago`. Pass `now` from the server render so the text is stable during hydration. */
export function formatRelative(iso: string, now: Date | number = Date.now()): string {
  const diff = new Date(iso).getTime() - (typeof now === "number" ? now : now.getTime());
  for (const [unit, ms] of UNITS) {
    if (Math.abs(diff) >= ms || unit === "minute") {
      return relative.format(Math.round(diff / ms), unit);
    }
  }
  return relative.format(0, "minute");
}
