package com.paypalseed.deskquery.domain.model;

import java.util.List;
import java.util.Set;

/** What one query parameter may contain. Anything else is rejected before it reaches SQL. */
public sealed interface FilterDefinition {

    String name();

    /** Validates and normalises the raw values; throws {@link IllegalArgumentException} naming the problem. */
    List<String> validate(List<String> raw);

    /** One or more values from a fixed set. The values are bound as parameters; they are never part of the SQL text. */
    record OneOf(String name, Set<String> allowed) implements FilterDefinition {
        @Override
        public List<String> validate(List<String> raw) {
            for (String v : raw) {
                if (!allowed.contains(v)) {
                    throw new IllegalArgumentException("'" + name + "' does not accept that value");
                }
            }
            return raw.stream().distinct().sorted().toList();
        }
    }

    /** {@code true} or {@code false}, once. */
    record Flag(String name) implements FilterDefinition {
        @Override
        public List<String> validate(List<String> raw) {
            if (raw.size() != 1 || !(raw.get(0).equals("true") || raw.get(0).equals("false"))) {
                throw new IllegalArgumentException("'" + name + "' must be true or false");
            }
            return List.of(raw.get(0));
        }
    }
}
