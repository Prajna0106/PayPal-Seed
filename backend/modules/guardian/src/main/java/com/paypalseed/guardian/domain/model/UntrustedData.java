package com.paypalseed.guardian.domain.model;

import java.util.regex.Pattern;

/**
 * Wraps tool output in {@code <tool_result>} tags (04 §7.1). The prompts tell the model that whatever is
 * inside is data, never instructions; stripping the tags from the content stops a value from closing the
 * block early and smuggling text outside it.
 */
public final class UntrustedData {

    private static final Pattern TAG = Pattern.compile("(?i)</?\\s*tool_result[^>]*>?");
    private static final Pattern NAME = Pattern.compile("[a-z_]{1,40}");

    private UntrustedData() {
    }

    public static String wrap(String tool, String content) {
        String name = NAME.matcher(tool).matches() ? tool : "unknown";
        String body = content == null ? "" : TAG.matcher(content).replaceAll("");
        return "<tool_result tool=\"" + name + "\">\n" + body + "\n</tool_result>";
    }
}
