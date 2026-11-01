package com.paypalseed.guardian.domain.model;

/**
 * Strict JSON schemas for the Guardian's structured answers. Strict mode needs every property listed in
 * {@code required}, no extra properties and single types, so lengths are checked by the validator instead.
 */
public final class Schemas {

    public static final String SECOND_OPINION_NAME = "guardian_second_opinion";
    public static final String NARRATIVE_NAME = "guardian_narrative";

    public static final String SECOND_OPINION = """
            {"type":"object","additionalProperties":false,"required":["proposedLevel","escalated","citedSignals","reason"],\
            "properties":{"proposedLevel":{"type":"string","enum":["NONE","WATCH","LIKELY_MISS","IMMINENT"]},\
            "escalated":{"type":"boolean"},"citedSignals":{"type":"array","items":{"type":"string"}},\
            "reason":{"type":"string"}}}""";

    public static final String NARRATIVE = """
            {"type":"object","additionalProperties":false,"required":["officerBrief","memberMessage","figures"],\
            "properties":{"officerBrief":{"type":"string"},\
            "memberMessage":{"type":"object","additionalProperties":false,"required":["en","es"],\
            "properties":{"en":{"type":"string"},"es":{"type":"string"}}},\
            "figures":{"type":"array","items":{"type":"object","additionalProperties":false,\
            "required":["value","unit","kind","tool","field"],\
            "properties":{"value":{"type":"string"},"unit":{"type":"string","enum":["USD_CENTS","PERCENT","DAYS","DATE","COUNT"]},\
            "kind":{"type":"string"},"tool":{"type":"string","enum":["get_loan_summary","get_repayment_schedule",\
            "get_loan_aging","get_hardship_signals","get_income_summary","list_offered_options","preview_option",\
            "list_prior_cases"]},"field":{"type":"string"}}}}}}""";

    private Schemas() {
    }
}
