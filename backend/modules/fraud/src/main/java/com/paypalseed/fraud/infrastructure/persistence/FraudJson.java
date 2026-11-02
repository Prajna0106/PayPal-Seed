package com.paypalseed.fraud.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/** Maps the jsonb columns to plain Java values. Only counts, times, ids and allow-listed labels are stored in them. */
@Component
class FraudJson {

    private static final TypeReference<LinkedHashMap<String, Object>> OBJECTS = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, String>> STRINGS = new TypeReference<>() {
    };

    private final ObjectMapper json;

    FraudJson(ObjectMapper json) {
        this.json = json;
    }

    JSONB write(Object value) {
        try {
            return JSONB.valueOf(json.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a fraud column", e);
        }
    }

    Map<String, Object> objects(JSONB value) {
        return read(value, OBJECTS);
    }

    Map<String, String> strings(JSONB value) {
        return read(value, STRINGS);
    }

    private <T> T read(JSONB value, TypeReference<T> type) {
        try {
            return json.readValue(value.data(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read a fraud column", e);
        }
    }
}
