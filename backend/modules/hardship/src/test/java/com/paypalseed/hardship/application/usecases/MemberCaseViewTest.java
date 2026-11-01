package com.paypalseed.hardship.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.hardship.domain.model.CaseRow;
import com.paypalseed.hardship.domain.model.CaseRow.Origin;
import com.paypalseed.hardship.domain.model.CaseStatus;
import com.paypalseed.hardship.infrastructure.persistence.CaseRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a member may see of their own open case: the payment at stake and whether options wait, and nothing about risk. */
class MemberCaseViewTest {

    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOAN = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CASE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private CaseRepository cases;
    private HardshipFacadeService facade;

    @BeforeEach
    void setUp() {
        cases = mock(CaseRepository.class);
        facade = new HardshipFacadeService(cases, null, null, null, null, null, null);
    }

    private void open(Optional<CaseRow> row) {
        when(cases.latestOpenForMember(MEMBER)).thenReturn(row);
    }

    private static CaseRow row(Origin origin, CaseStatus status) {
        return new CaseRow(CASE, LOAN, MEMBER, origin, status, UUID.randomUUID(), 1, 2, LocalDate.of(2026, 11, 20), 15_000,
            "LIKELY_MISS", 55, "policy-v1", "scorecard-v1", "INCOME_DROP", "dedupe", LocalDate.of(2026, 11, 10), "TEMPLATE",
            Instant.EPOCH, null, null, null, null, null, null, null, null);
    }

    @Test
    void anOfferWaitingForTheMemberIsShownWithTheDueDateAndTheAmountAtStake() {
        open(Optional.of(row(Origin.PROACTIVE_SCAN, CaseStatus.OUTREACH_SENT)));

        MemberCaseView view = facade.openCaseOf(MEMBER).orElseThrow();

        assertThat(view.caseId()).isEqualTo(CASE);
        assertThat(view.loanId()).isEqualTo(LOAN);
        assertThat(view.status()).isEqualTo("OUTREACH_SENT");
        assertThat(view.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(view.amountAtStakeCents()).isEqualTo(15_000);
        assertThat(view.offerWaiting()).isTrue();
        assertThat(view.toString()).as("no risk level, points or reason for the member")
            .doesNotContain("LIKELY_MISS").doesNotContain("55").doesNotContain("INCOME_DROP").doesNotContain("PROACTIVE");
    }

    @Test
    void aViewedOfferIsStillWaitingButACaseTheMemberAlreadyChoseOnIsNot() {
        open(Optional.of(row(Origin.PROACTIVE_SCAN, CaseStatus.OFFER_VIEWED)));
        assertThat(facade.openCaseOf(MEMBER).orElseThrow().offerWaiting()).isTrue();

        for (CaseStatus chosen : new CaseStatus[] {CaseStatus.OPTION_CHOSEN, CaseStatus.PENDING_APPROVAL, CaseStatus.APPROVED}) {
            open(Optional.of(row(Origin.MEMBER_HELP, chosen)));
            assertThat(facade.openCaseOf(MEMBER).orElseThrow().offerWaiting()).as(chosen.name()).isFalse();
        }
    }

    @Test
    void aCaseFromAScanThatHasNotReachedTheMemberYetIsNotShown() {
        open(Optional.of(row(Origin.PROACTIVE_SCAN, CaseStatus.DETECTED)));

        assertThat(facade.openCaseOf(MEMBER)).isEmpty();
    }

    @Test
    void aCaseTheMemberAskedForIsShownEvenBeforeAnOfferIsReady() {
        open(Optional.of(row(Origin.MEMBER_HELP, CaseStatus.DETECTED)));

        MemberCaseView view = facade.openCaseOf(MEMBER).orElseThrow();

        assertThat(view.status()).isEqualTo("DETECTED");
        assertThat(view.offerWaiting()).isFalse();
    }

    @Test
    void noOpenCaseIsNothing() {
        open(Optional.empty());

        assertThat(facade.openCaseOf(MEMBER)).isEmpty();
    }
}
