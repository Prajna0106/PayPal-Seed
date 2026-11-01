package com.paypalseed.reserve.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

/** A donor's chosen display name, cleaned so it is safe to show on a public page. */
public final class DonorName {

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");

    public static final int MAX_LENGTH = 40;

    private DonorName() {
    }

    /**
     * Keeps letters, digits, spaces and a few name marks; drops everything else (markup, control characters,
     * links), collapses runs of spaces and cuts at {@link #MAX_LENGTH} characters. A name with nothing left is absent.
     */
    public static Optional<String> clean(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = TAGS.matcher(raw).replaceAll(" ");
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        int count = 0;
        int i = 0;
        while (i < text.length() && count < MAX_LENGTH) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) {
                pendingSpace = count > 0;
            } else if (Character.isLetterOrDigit(cp) || cp == '.' || cp == '\'' || cp == '-' || cp == '&') {
                if (pendingSpace) {
                    out.append(' ');
                    count++;
                    pendingSpace = false;
                    if (count >= MAX_LENGTH) {
                        break;
                    }
                }
                out.appendCodePoint(cp);
                count++;
            }
        }
        String name = out.toString().strip();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }
}
