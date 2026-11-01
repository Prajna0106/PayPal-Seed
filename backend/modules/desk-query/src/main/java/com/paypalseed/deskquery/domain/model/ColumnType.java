package com.paypalseed.deskquery.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The type of a sortable value, and its one text form. A cursor carries the last row's sort values as text, so the
 * text form must round-trip exactly: {@code parse(format(v)).equals(v)}.
 */
public enum ColumnType {
    STRING,
    INT,
    LONG,
    DATE,
    INSTANT,
    ID;

    public Object parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("null value");
        }
        return switch (this) {
            case STRING -> raw;
            case INT -> Integer.valueOf(raw);
            case LONG -> Long.valueOf(raw);
            case DATE -> LocalDate.parse(raw);
            case INSTANT -> Instant.parse(raw);
            case ID -> UUID.fromString(raw);
        };
    }

    public String format(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("sort values are never null; coalesce nullable columns");
        }
        return value.toString();
    }
}
