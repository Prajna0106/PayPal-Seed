package com.paypalseed.hardship.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.hardship.domain.model.OfferModel.ProposedRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/** Maps the jsonb columns to plain Java values. Only numbers, labels and dates are ever stored in them. */
@Component
class JsonColumns {

    private static final TypeReference<LinkedHashMap<String, Long>> LONG_MAP = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<String, Integer>> INT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<>() {
    };

    private final ObjectMapper json;

    JsonColumns(ObjectMapper json) {
        this.json = json;
    }

    JSONB write(Object value) {
        try {
            return JSONB.valueOf(json.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise a hardship column", e);
        }
    }

    Map<String, Long> longs(JSONB value) {
        return read(value, LONG_MAP);
    }

    Map<String, Integer> ints(JSONB value) {
        return read(value, INT_MAP);
    }

    List<String> strings(JSONB value) {
        return read(value, STRINGS);
    }

    JSONB writeRows(List<ProposedRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProposedRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("dueDate", r.dueDate().toString());
            m.put("amountCents", r.amountCents());
            m.put("remainingCents", r.remainingCents());
            out.add(m);
        }
        return write(out);
    }

    List<ProposedRow> rows(JSONB value) {
        List<ProposedRow> out = new ArrayList<>();
        for (Map<String, Object> m : read(value, ROWS)) {
            out.add(new ProposedRow(((Number) m.get("seq")).intValue(), LocalDate.parse((String) m.get("dueDate")),
                    ((Number) m.get("amountCents")).longValue(), ((Number) m.get("remainingCents")).longValue()));
        }
        return out;
    }

    private <T> T read(JSONB value, TypeReference<T> type) {
        try {
            return json.readValue(value.data(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot read a hardship column", e);
        }
    }
}
