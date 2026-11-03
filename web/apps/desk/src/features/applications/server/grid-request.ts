import "server-only";

import { z } from "zod";

/** Largest block AG Grid may ask for; the backend caps a page at 200 as well. */
const MAX_BLOCK = 200;
/** A cursor is `base64url(json).base64url(hmac)`; anything else is refused before it reaches the backend. */
const CURSOR = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

const sortItem = z.object({ colId: z.string().max(40), sort: z.enum(["asc", "desc"]) });

/** The grid sends its sort and filter models as JSON text in the query string. Too long or not JSON: refused. */
function readJson(raw: string | null, whenMissing: unknown): { ok: true; value: unknown } | { ok: false } {
  if (!raw) {
    return { ok: true, value: whenMissing };
  }
  if (raw.length > 4000) {
    return { ok: false };
  }
  try {
    return { ok: true, value: JSON.parse(raw) as unknown };
  } catch {
    return { ok: false };
  }
}

/** One sortable grid column: the backend's column name, and whether the grid's direction runs the other way. */
export interface SortColumn {
  column: string;
  invert?: boolean;
}

export interface GridRequest<Filters> {
  startRow: number;
  limit: number;
  cursor: string | undefined;
  /** The backend's `sort` parameter (`col:dir,col:dir`), or undefined for its default order. */
  sort: string | undefined;
  filters: Filters;
}

/**
 * Translates AG Grid's infinite-row-model request (`startRow`, `endRow`, `sortModel`, `filterModel`, plus the
 * opaque cursor the client holds) into the backend's keyset parameters. Column names come only from the allow-list
 * in `columns`; nothing the browser sends is passed on as free text.
 */
export function parseGridRequest<Filters extends z.ZodType>(
  searchParams: URLSearchParams,
  columns: Record<string, SortColumn>,
  filterSchema: Filters,
): { ok: true; value: GridRequest<z.output<Filters>> } | { ok: false; message: string } {
  const schema = z
    .object({
      startRow: z.coerce.number().int().min(0).max(1_000_000),
      endRow: z.coerce.number().int().min(1).max(1_001_000),
      sortModel: z.array(sortItem).max(3),
      cursor: z.string().max(2048).regex(CURSOR).optional(),
    })
    .refine((value) => value.endRow > value.startRow && value.endRow - value.startRow <= MAX_BLOCK, {
      message: "Ask for between 1 and 200 rows.",
    })
    .refine((value) => value.startRow === 0 || value.cursor !== undefined, {
      message: "A cursor is needed to continue past the first page.",
    });

  const sortJson = readJson(searchParams.get("sortModel"), []);
  const filterJson = readJson(searchParams.get("filterModel"), {});
  if (!sortJson.ok || !filterJson.ok) {
    return { ok: false, message: "That list request is not valid." };
  }
  const filterModel = filterSchema.safeParse(filterJson.value);
  const parsed = schema.safeParse({
    startRow: searchParams.get("startRow") ?? undefined,
    endRow: searchParams.get("endRow") ?? undefined,
    sortModel: sortJson.value,
    cursor: searchParams.get("cursor") ?? undefined,
  });
  if (!parsed.success || !filterModel.success) {
    return { ok: false, message: "That list request is not valid." };
  }

  const { startRow, endRow, sortModel, cursor } = parsed.data;
  const keys: string[] = [];
  const seen = new Set<string>();
  for (const item of sortModel) {
    const column = Object.hasOwn(columns, item.colId) ? columns[item.colId] : undefined;
    if (!column || seen.has(column.column)) {
      return { ok: false, message: "That sort is not available." };
    }
    seen.add(column.column);
    const descending = (item.sort === "desc") !== (column.invert ?? false);
    keys.push(`${column.column}:${descending ? "desc" : "asc"}`);
  }

  return {
    ok: true,
    value: {
      startRow,
      limit: endRow - startRow,
      cursor,
      sort: keys.length > 0 ? keys.join(",") : undefined,
      filters: filterModel.data,
    },
  };
}
