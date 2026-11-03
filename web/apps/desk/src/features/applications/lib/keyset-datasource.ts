import type { IDatasource, IGetRowsParams } from "ag-grid-community";

/** What the queue routes (`/api/applications`, `/api/fair-checks`) answer for one block of rows. */
export interface KeysetPage<Row> {
  rowData: Row[];
  /** Set only when the backend has no further page: the grid then knows the end. */
  lastRow?: number;
  nextCursor?: string;
  total?: { value: number; exact: boolean };
  asOf?: string;
}

export interface KeysetDatasourceOptions<Filters> {
  /** Same-origin route handler; the browser never talks to the backend. */
  endpoint: string;
  /** The filters the list starts with; `setFilters` changes them, after which the grid must purge its cache. */
  initialFilters: Filters;
  onFirstPage?: (page: KeysetPage<never>) => void;
  onError?: (message: string | null) => void;
}

export interface KeysetDatasource<Filters> extends IDatasource {
  /** Forgets every cursor. Call it before refreshing so the next load starts from the first page. */
  reset: () => void;
  setFilters: (filters: Filters) => void;
}

class PageError extends Error {
  constructor(
    message: string,
    readonly code?: string,
  ) {
    super(message);
  }
}

/**
 * AG Grid's infinite row model over a keyset API. The backend can only continue from the page it just served, so
 * the cursor of each block is held here, per sort and filter signature (a cursor is bound to both): a block asked
 * for out of order is reached by walking forward from the nearest block that is known. Cursors stay opaque.
 */
export function createKeysetDatasource<Row, Filters>(options: KeysetDatasourceOptions<Filters>): KeysetDatasource<Filters> {
  let filters = options.initialFilters;
  const cursorsBySignature = new Map<string, Map<number, string>>();

  async function fetchPage(
    startRow: number,
    blockSize: number,
    cursor: string | undefined,
    sortModel: unknown,
    filters: Filters,
  ): Promise<KeysetPage<Row>> {
    const query = new URLSearchParams({
      startRow: String(startRow),
      endRow: String(startRow + blockSize),
      sortModel: JSON.stringify(sortModel),
      filterModel: JSON.stringify(filters),
    });
    if (cursor) {
      query.set("cursor", cursor);
    }
    const response = await fetch(`${options.endpoint}?${query.toString()}`, { cache: "no-store" });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as { title?: string; code?: string };
      throw new PageError(body.title ?? "The list could not be loaded.", body.code);
    }
    return (await response.json()) as KeysetPage<Row>;
  }

  async function load(params: IGetRowsParams): Promise<void> {
    const { startRow, endRow, sortModel } = params;
    const blockSize = endRow - startRow;
    const signature = JSON.stringify([sortModel, filters]);
    let known = cursorsBySignature.get(signature);
    if (!known) {
      known = new Map();
      cursorsBySignature.set(signature, known);
    }

    try {
      let from = 0;
      for (const key of known.keys()) {
        if (key < startRow && key > from) {
          from = key;
        }
      }
      while (from < startRow) {
        const skipped = await fetchPage(from, blockSize, known.get(from), sortModel, filters);
        if (!skipped.nextCursor) {
          params.successCallback([], skipped.lastRow ?? from + skipped.rowData.length);
          return;
        }
        from += blockSize;
        known.set(from, skipped.nextCursor);
      }

      const page = await fetchPage(startRow, blockSize, known.get(startRow), sortModel, filters);
      if (page.nextCursor) {
        known.set(startRow + blockSize, page.nextCursor);
      }
      if (startRow === 0) {
        options.onFirstPage?.(page as KeysetPage<never>);
      }
      options.onError?.(null);
      params.successCallback(page.rowData, page.nextCursor ? undefined : (page.lastRow ?? startRow + page.rowData.length));
    } catch (reason) {
      if (reason instanceof PageError && reason.code === "desk-query-invalid-cursor") {
        cursorsBySignature.clear();
      }
      options.onError?.(reason instanceof Error ? reason.message : "The list could not be loaded.");
      params.failCallback();
    }
  }

  return {
    getRows: (params) => {
      void load(params);
    },
    reset: () => cursorsBySignature.clear(),
    setFilters: (next) => {
      filters = next;
    },
  };
}
