import { themeQuartz } from "ag-grid-community";

/** AG Grid's Quartz theme, re-coloured with Seed's tokens so a queue reads as part of the desk, not a widget. */
export const seedGridTheme = themeQuartz.withParams({
  fontFamily: "var(--font-inter), ui-sans-serif, system-ui, sans-serif",
  headerFontFamily: "var(--font-inter), ui-sans-serif, system-ui, sans-serif",
  fontSize: 14,
  headerFontSize: 12,
  headerFontWeight: 500,
  foregroundColor: "var(--color-ink)",
  textColor: "var(--color-ink)",
  backgroundColor: "var(--color-surface)",
  headerBackgroundColor: "var(--color-canvas-soft)",
  headerTextColor: "var(--color-muted-ink)",
  borderColor: "var(--color-hairline)",
  rowBorder: { color: "var(--color-hairline-soft)" },
  columnBorder: false,
  wrapperBorder: { color: "var(--color-hairline)" },
  wrapperBorderRadius: 16,
  borderRadius: 8,
  accentColor: "var(--color-ink)",
  rowHoverColor: "var(--color-canvas-soft)",
  selectedRowBackgroundColor: "var(--color-surface-strong)",
  oddRowBackgroundColor: "var(--color-surface)",
  spacing: 7,
  rowHeight: 52,
  headerHeight: 44,
  cellHorizontalPadding: 16,
});
