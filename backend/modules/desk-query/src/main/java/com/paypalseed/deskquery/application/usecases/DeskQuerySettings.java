package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.CursorCodec;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tunables of the desk read side. Every key has a safe default; set the cursor key in any deployment with more than one api
 * instance, so a cursor issued by one is accepted by the others.
 */
@Component
public class DeskQuerySettings {

    private final Duration applicationSla;
    private final Duration dueSoonWindow;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final int countCap;
    private final CursorCodec cursors;

    DeskQuerySettings(@Value("${seed.desk-query.application-sla:PT24H}") Duration applicationSla,
                      @Value("${seed.desk-query.sla-due-soon:PT4H}") Duration dueSoonWindow,
                      @Value("${seed.desk-query.default-page-size:50}") int defaultPageSize,
                      @Value("${seed.desk-query.max-page-size:200}") int maxPageSize,
                      @Value("${seed.desk-query.count-cap:10000}") int countCap,
                      @Value("${seed.desk-query.cursor-key:}") String cursorKey) {
        this.applicationSla = applicationSla;
        this.dueSoonWindow = dueSoonWindow;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.countCap = countCap;
        this.cursors = new CursorCodec(cursorKey);
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
