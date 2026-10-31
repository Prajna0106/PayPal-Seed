package com.paypalseed.hardship.domain.model;

import com.paypalseed.platform.kernel.DomainException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The hardship case lifecycle (02-design §3.1). Every status change goes through {@link #requireNext}, so an
 * illegal transition fails loudly instead of corrupting a case.
 */
public enum CaseStatus {
    DETECTED,
    OUTREACH_SENT,
    OFFER_VIEWED,
    OPTION_CHOSEN,
    PENDING_APPROVAL,
    APPROVED,
    APPROVED_EXECUTION_FAILED,
    APPLIED,
    DECLINED_BY_MEMBER,
    REJECTED_BY_OFFICER,
    EXPIRED,
    CANCELLED_PAID,
    SUPERSEDED;

    private static final Set<CaseStatus> TERMINAL =
            EnumSet.of(APPLIED, DECLINED_BY_MEMBER, REJECTED_BY_OFFICER, EXPIRED, CANCELLED_PAID, SUPERSEDED);

    private static final Map<CaseStatus, Set<CaseStatus>> NEXT = Map.ofEntries(
            Map.entry(DETECTED, EnumSet.of(OUTREACH_SENT, OFFER_VIEWED, DECLINED_BY_MEMBER, EXPIRED, CANCELLED_PAID, SUPERSEDED)),
            Map.entry(OUTREACH_SENT, EnumSet.of(OFFER_VIEWED, OPTION_CHOSEN, DECLINED_BY_MEMBER, EXPIRED, CANCELLED_PAID,
                    SUPERSEDED)),
            Map.entry(OFFER_VIEWED, EnumSet.of(OPTION_CHOSEN, DECLINED_BY_MEMBER, EXPIRED, CANCELLED_PAID, SUPERSEDED)),
            Map.entry(OPTION_CHOSEN, EnumSet.of(PENDING_APPROVAL, APPROVED, OFFER_VIEWED, DECLINED_BY_MEMBER, EXPIRED,
                    CANCELLED_PAID, SUPERSEDED)),
            Map.entry(PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED_BY_OFFICER, OFFER_VIEWED, DECLINED_BY_MEMBER,
                    CANCELLED_PAID, SUPERSEDED)),
            Map.entry(APPROVED, EnumSet.of(APPLIED, APPROVED_EXECUTION_FAILED, OFFER_VIEWED, CANCELLED_PAID, SUPERSEDED)),
            Map.entry(APPROVED_EXECUTION_FAILED, EnumSet.of(APPROVED, CANCELLED_PAID, SUPERSEDED)));

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /** States in which the member may still choose or decline. */
    public boolean awaitsMember() {
        return this == DETECTED || this == OUTREACH_SENT || this == OFFER_VIEWED;
    }

    public boolean canMoveTo(CaseStatus next) {
        return NEXT.getOrDefault(this, Set.of()).contains(next);
    }

    public void requireNext(CaseStatus next) {
        if (!canMoveTo(next)) {
            throw new DomainException(HardshipErrors.CASE_CLOSED, "A case in " + this + " cannot move to " + next);
        }
    }

    public static Set<CaseStatus> terminalStates() {
        return EnumSet.copyOf(TERMINAL);
    }
}
