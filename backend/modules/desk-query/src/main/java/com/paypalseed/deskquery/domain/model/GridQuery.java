package com.paypalseed.deskquery.domain.model;

import java.util.List;
import java.util.Map;

/**
 * A validated grid request: only allow-listed sort columns and filter values survive into this record. {@code after}
 * holds the sort values of the last row of the previous page (one per sort key, then the row id), or is null for the
 * first page.
 */
public record GridQuery(String grid, List<SortKey> sort, Map<String, List<String>> filters, int limit, List<String> after) {

    public GridQuery {
        sort = List.copyOf(sort);
        filters = Map.copyOf(filters);
        after = after == null ? null : List.copyOf(after);
    }

    public boolean firstPage() {
        return after == null;
    }

    public boolean hasFilter(String name) {
        return filters.containsKey(name);
    }

    public List<String> values(String name) {
        return filters.getOrDefault(name, List.of());
    }

    /** The boolean filter, or null when the request did not set it. */
    public Boolean flag(String name) {
        List<String> v = filters.get(name);
        return v == null ? null : Boolean.valueOf(v.get(0));
    }

    /** The sort in its canonical text form, which a cursor is bound to. */
    public String sortSpec() {
        return String.join(",", sort.stream().map(SortKey::spec).toList());
    }

    /** A stable text form of the filters, which a cursor is bound to. */
    public String filterSpec() {
        StringBuilder sb = new StringBuilder();
        filters.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(String.join(",", e.getValue())).append(';'));
        return sb.toString();
    }
}
