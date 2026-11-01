package com.paypalseed.governance.application.usecases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import java.util.Map;
import java.util.TreeMap;

/**
 * One text form per JSON value: object keys sorted, no whitespace, numbers written exactly (no exponent,
 * scale kept). The same value read back from Postgres {@code jsonb} gives the same text, so hashes taken at
 * write time can be recomputed from the stored rows.
 */
public final class CanonicalJson {

    private static final ObjectMapper READER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    private static final ObjectMapper WRITER = new ObjectMapper();

    private CanonicalJson() {
    }

    /** Throws {@link IllegalArgumentException} for text that is not JSON. */
    public static JsonNode parse(String json) {
        try {
            return READER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not valid JSON", e);
        }
    }

    public static String of(String json) {
        return of(parse(json));
    }

    public static String of(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                sorted.put(e.getKey(), e.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(e.getKey())).append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            out.append(quote(node.textValue()));
        } else if (node.isBigDecimal() || node.isFloatingPointNumber()) {
            out.append(node.decimalValue().toPlainString());
        } else if (node.isNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else {
            throw new IllegalArgumentException("unsupported JSON node: " + node.getNodeType());
        }
    }

    private static String quote(String text) {
        try {
            return WRITER.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a string always serialises", e);
        }
    }
}
