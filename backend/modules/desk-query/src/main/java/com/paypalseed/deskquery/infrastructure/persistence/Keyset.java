package com.paypalseed.deskquery.infrastructure.persistence;

import java.util.List;

/** One page of rows. {@code lastKeys} are the sort values of the last row when more rows follow, otherwise null. */
public record Keyset<R>(List<R> rows, List<String> lastKeys) {

    public boolean hasMore() {
        return lastKeys != null;
    }
}
