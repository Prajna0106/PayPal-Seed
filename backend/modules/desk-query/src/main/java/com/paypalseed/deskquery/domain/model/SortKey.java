package com.paypalseed.deskquery.domain.model;

/** One column of a requested sort, by its public name. */
public record SortKey(String column, boolean descending) {

    public String spec() {
        return column + ":" + (descending ? "desc" : "asc");
    }
}
