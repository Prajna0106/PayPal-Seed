package com.paypalseed.hardship.application.usecases;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseRow.Origin;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import com.paypalseed.lending.api.LendingFacade;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A loan that never stood (ADR 0024) leaves nothing for an open case to protect. */
class CaseReactionsLoanVoidedTest {

    private final CaseRepository cases = mock(CaseRepository.class);
    private final CaseLifecycle lifecycle = mock(CaseLifecycle.class);
    private final CaseReactions reactions = new CaseReactions(cases, lifecycle, mock(LendingFacade.class));

    private static CaseRow caseIn(UUID loan, CaseStatus status) {
        return new CaseRow(UUID.randomUUID(), loan, UUID.randomUUID(), Origin.PROACTIVE_SCAN, status, null, 1, 1,
                LocalDate.of(2026, 12, 15), 15_000, "LIKELY_MISS", 5, "p1", "s1", null, "k", LocalDate.of(2026, 12, 7), "TEMPLATE", null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void anOpenCaseOnTheVoidedLoanEndsWithAClearReasonAndNoOutreachCanFollow() {
        UUID loan = UUID.randomUUID();
        for (CaseStatus open : new CaseStatus[] {CaseStatus.DETECTED, CaseStatus.OUTREACH_SENT, CaseStatus.PENDING_APPROVAL,
                CaseStatus.APPROVED, CaseStatus.APPROVED_EXECUTION_FAILED}) {
            CaseRow c = caseIn(loan, open);
            when(cases.openForLoan(loan)).thenReturn(Optional.of(c));
            when(cases.lock(c.id())).thenReturn(Optional.of(c));

            reactions.onLoanVoided(loan);

            verify(lifecycle).close(c, CaseStatus.SUPERSEDED, "LOAN_VOIDED");
        }
    }

    @Test
    void aLoanWithoutAnOpenCaseOrAReplayAfterTheCloseChangesNothing() {
        UUID loan = UUID.randomUUID();
        when(cases.openForLoan(loan)).thenReturn(Optional.empty());
        reactions.onLoanVoided(loan);

        CaseRow closed = caseIn(loan, CaseStatus.SUPERSEDED);
        when(cases.openForLoan(loan)).thenReturn(Optional.of(closed));
        when(cases.lock(closed.id())).thenReturn(Optional.of(closed));
        reactions.onLoanVoided(loan);

        verify(lifecycle, never()).close(any(), any(), any());
    }
}
