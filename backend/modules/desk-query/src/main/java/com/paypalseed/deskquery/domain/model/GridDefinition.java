package com.paypalseed.deskquery.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The public face of one desk list: which columns can be sorted and which parameters can filter. Names here are the
 * only way a request reaches a column; the persistence layer maps each to a fixed expression, so no request text is
 * ever turned into SQL.
 */
public final class GridDefinition {

    public static final int MAX_SORT_KEYS = 3;
    public static final String SORT = "sort";
    public static final String LIMIT = "limit";
    public static final String CURSOR = "cursor";
    private static final Set<String> RESERVED = Set.of(SORT, LIMIT, CURSOR);

    private final String name;
    private final Map<String, ColumnType> sortable;
    private final Map<String, FilterDefinition> filters;
    private final List<SortKey> defaultSort;

    public GridDefinition(String name, Map<String, ColumnType> sortable, List<FilterDefinition> filters, List<SortKey> defaultSort) {
        this.name = name;
        this.sortable = Map.copyOf(sortable);
        Map<String, FilterDefinition> byName = new LinkedHashMap<>();
        filters.forEach(f -> byName.put(f.name(), f));
        this.filters = Map.copyOf(byName);
        this.defaultSort = List.copyOf(defaultSort);
        for (SortKey k : defaultSort) {
            if (!sortable.containsKey(k.column())) {
                throw new IllegalArgumentException("default sort column is not sortable: " + k.column());
            }
        }
    }

    public String name() {
        return name;
    }

    public Set<String> sortableNames() {
        return sortable.keySet();
    }

    public Set<String> filterNames() {
        return filters.keySet();
    }

    /** The type of the sort key at {@code index}; the key after the last sort key is the row id. */
    public ColumnType keyType(List<SortKey> sort, int index) {
        return index < sort.size() ? sortable.get(sort.get(index).column()) : ColumnType.ID;
    }

    /**
     * @param sort   {@code col[:asc|desc][,...]} or null for the default
     * @param params every other query parameter, as received
     * @param limit  requested page size or null for {@code defaultLimit}
     * @param cursor the token of the previous page or null
     */
    public GridQuery parse(String sort, Map<String, List<String>> params, Integer limit, String cursor, int defaultLimit,
                           int maxLimit, CursorCodec codec) {
        List<SortKey> keys = parseSort(sort);
        Map<String, List<String>> checked = parseFilters(params);
        int size = limit == null ? defaultLimit : limit;
        if (size < 1 || size > maxLimit) {
            throw new DomainException(DeskQueryErrors.INVALID_LIMIT, "limit must be between 1 and " + maxLimit);
        }
        GridQuery unpaged = new GridQuery(name, keys, checked, size, null);
        if (cursor == null || cursor.isBlank()) {
            return unpaged;
        }
        List<String> after = codec.decode(cursor, unpaged);
        if (after.size() != keys.size() + 1) {
            throw new DomainException(DeskQueryErrors.INVALID_CURSOR, "The cursor does not belong to this request");
        }
        try {
            for (int i = 0; i < after.size(); i++) {
                keyType(keys, i).parse(after.get(i));
            }
        } catch (RuntimeException e) {
            throw new DomainException(DeskQueryErrors.INVALID_CURSOR, "The cursor does not belong to this request");
        }
        return new GridQuery(name, keys, checked, size, after);
    }

    private List<SortKey> parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }
        List<SortKey> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : sort.split(",", -1)) {
            String[] bits = part.split(":", -1);
            if (bits.length > 2 || !sortable.containsKey(bits[0])) {
                throw new DomainException(DeskQueryErrors.INVALID_SORT, "Sort by one of " + new TreeSet<>(sortable.keySet()));
            }
            boolean desc;
            if (bits.length == 1 || bits[1].equals("asc")) {
                desc = false;
            } else if (bits[1].equals("desc")) {
                desc = true;
            } else {
                throw new DomainException(DeskQueryErrors.INVALID_SORT, "The direction is asc or desc");
            }
            if (!seen.add(bits[0])) {
                throw new DomainException(DeskQueryErrors.INVALID_SORT, "'" + bits[0] + "' is sorted twice");
            }
            keys.add(new SortKey(bits[0], desc));
        }
        if (keys.size() > MAX_SORT_KEYS) {
            throw new DomainException(DeskQueryErrors.INVALID_SORT, "Sort by at most " + MAX_SORT_KEYS + " columns");
        }
        return keys;
    }

    private Map<String, List<String>> parseFilters(Map<String, List<String>> params) {
        Map<String, List<String>> checked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            if (RESERVED.contains(e.getKey())) {
                continue;
            }
            FilterDefinition def = filters.get(e.getKey());
            if (def == null) {
                throw new DomainException(DeskQueryErrors.INVALID_FILTER, "Filter by one of " + new TreeSet<>(filters.keySet()));
            }
            List<String> raw = e.getValue().stream().flatMap(v -> Arrays.stream(v.split(",", -1)))
                    .map(String::trim).filter(v -> !v.isEmpty()).toList();
            if (raw.isEmpty()) {
                continue;
            }
            try {
                checked.put(e.getKey(), def.validate(raw));
            } catch (IllegalArgumentException ex) {
                throw new DomainException(DeskQueryErrors.INVALID_FILTER, ex.getMessage());
            }
        }
        return checked;
    }
}
