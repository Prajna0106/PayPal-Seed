package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.CursorCodec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Tunables of the desk read side. Every key has a safe default except the cursor key: a cursor is signed with it, so a page
 * token issued by one api instance is only accepted by another when both hold the same key. Outside the dev, test and demo
 * profiles an empty key therefore stops the start instead of quietly giving every instance its own random key (ADR 0027).
 * Those three profiles keep generating a key per process.
 */
@Component
public class DeskQuerySettings {

    static final String CURSOR_KEY_PROPERTY = "seed.desk-query.cursor-key";
    private static final Set<String> DEVELOPMENT_PROFILES = Set.of("dev", "test", "demo");

    private final Duration applicationSla;
    private final Duration dueSoonWindow;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final int countCap;
    private final CursorCodec cursors;

    @Autowired
    DeskQuerySettings(@Value("${seed.desk-query.application-sla:PT24H}") Duration applicationSla,
                      @Value("${seed.desk-query.sla-due-soon:PT4H}") Duration dueSoonWindow,
                      @Value("${seed.desk-query.default-page-size:50}") int defaultPageSize,
                      @Value("${seed.desk-query.max-page-size:200}") int maxPageSize,
                      @Value("${seed.desk-query.count-cap:10000}") int countCap,
                      @Value("${seed.desk-query.cursor-key:}") String cursorKey,
                      Environment environment) {
        this(applicationSla, dueSoonWindow, defaultPageSize, maxPageSize, countCap, requireKeyOutsideDevelopment(cursorKey, environment));
    }

    /** For tests and callers that decide the key themselves; a blank key makes a random per-process one. */
    DeskQuerySettings(Duration applicationSla, Duration dueSoonWindow, int defaultPageSize, int maxPageSize, int countCap,
                      String cursorKey) {
        this.applicationSla = applicationSla;
        this.dueSoonWindow = dueSoonWindow;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.countCap = countCap;
        this.cursors = new CursorCodec(cursorKey);
    }

    /** Fails the start with a message that names the setting; the key itself is never part of it. */
    static String requireKeyOutsideDevelopment(String cursorKey, Environment environment) {
        boolean blank = cursorKey == null || cursorKey.isBlank();
        boolean development = Arrays.stream(environment.getActiveProfiles())
                .map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(DEVELOPMENT_PROFILES::contains);
        if (blank && !development) {
            throw new IllegalStateException(CURSOR_KEY_PROPERTY + " (SEED_DESK_QUERY_CURSOR_KEY) must be set, to the same value on every"
                    + " api instance, outside the dev, test and demo profiles; without it each instance signs desk page cursors"
                    + " with its own random key and a page opened on one instance fails on another");
        }
        return cursorKey;
    }

    /** How long an officer has to take the first decision on a submitted application. */
    public Duration applicationSla() {
        return applicationSla;
    }

    public Duration dueSoonWindow() {
        return dueSoonWindow;
    }

    public int defaultPageSize() {
        return defaultPageSize;
    }

    public int maxPageSize() {
        return maxPageSize;
    }

    /** Totals are exact up to this many rows and an estimate beyond it. */
    public int countCap() {
        return countCap;
    }

    public CursorCodec cursors() {
        return cursors;
    }
}
