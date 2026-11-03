package com.paypalseed.underwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.underwriting.application.ports.MemberConsentLedger;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import com.paypalseed.underwriting.application.usecases.IncomeConsentSync;
import com.paypalseed.underwriting.application.usecases.IncomeRefresher;
import com.paypalseed.underwriting.application.usecases.IncomeRefresher.FetchedIncome;
import com.paypalseed.underwriting.application.usecases.IncomeSummaryService;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.UnderwritingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** The fair-check consent is mirrored into the member's consent list, and income is prepared before any transaction (ADR 0026). */
class ConsentMirrorAndPrepareTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private final List<String> ledgerCalls = new ArrayList<>();
    private final MemberConsentLedger ledger = new MemberConsentLedger() {
        @Override
        public void fairCheckGiven(UUID memberId) {
            ledgerCalls.add("given:" + memberId);
        }

        @Override
        public void closed(UUID memberId) {
            ledgerCalls.add("closed:" + memberId);
        }

        @Override
        public boolean withdrawn(UUID memberId, Line line) {
            ledgerCalls.add("withdrawn:" + line);
            return !lineActiveAgain;
        }
    };
    private boolean lineActiveAgain;

    private FairLoanCheckService fairCheck;

    @BeforeEach
    void setUp() {
        fairCheck = new FairLoanCheckService(new UnderwritingRepository(), mock(SavingsFacade.class), null, null, null, null, ledger);
    }

    @Test
    void givingTheConsentRecordsTheFairCheckLineAndWithdrawingClosesTheLines() {
        assertThat(fairCheck.grantConsent(ANA).consented()).isTrue();
        assertThat(ledgerCalls).containsExactly("given:" + ANA);

        assertThat(fairCheck.revokeConsent(ANA).consented()).isFalse();
        assertThat(ledgerCalls).containsExactly("given:" + ANA, "closed:" + ANA);
    }

    @Test
    void withoutAConsentListTheConsentStillWorks() {
        FairLoanCheckService alone = new FairLoanCheckService(new UnderwritingRepository(), mock(SavingsFacade.class));

        assertThat(alone.grantConsent(ANA).consented()).isTrue();
        assertThat(alone.revokeConsent(ANA).consented()).isFalse();
    }

    @Test
    void aWithdrawnFairCheckAndAWithdrawnIncomeReadHaveTheSameIdempotentEffect() {
        IncomeRepository income = mock(IncomeRepository.class);
        when(income.consent(ANA)).thenReturn(Optional.of(new Consent(true, null))).thenReturn(Optional.of(new Consent(false, null)));
        IncomeConsentSync sync = new IncomeConsentSync(income, new FakeBusinessClock(TODAY), ledger);

        sync.fairCheckWithdrawn(ANA);
        sync.incomeReadWithdrawn(ANA);

        verify(income).revoke(any(), any());
        verify(income, times(2)).purgeFetched(ANA);
        assertThat(ledgerCalls).containsExactly("withdrawn:FAIR_CHECK", "withdrawn:INCOME_READ");
    }

    @Test
    void anOldWithdrawalDeliveredAfterTheMemberAgreedAgainChangesNothing() {
        IncomeRepository income = mock(IncomeRepository.class);
        when(income.consent(ANA)).thenReturn(Optional.of(new Consent(true, null)));
        IncomeConsentSync sync = new IncomeConsentSync(income, new FakeBusinessClock(TODAY), ledger);
        lineActiveAgain = true;

        sync.incomeReadWithdrawn(ANA);
        sync.fairCheckWithdrawn(ANA);

        verify(income, never()).revoke(any(), any());
        verify(income, never()).purgeFetched(any());
    }

    @Test
    void aFailedPayPalReadStoresNothingAndRecordsNoConsent() {
        IncomeRefresher refresher = mock(IncomeRefresher.class);
        FairLoanCheckService checks = mock(FairLoanCheckService.class);
        when(refresher.fetchIfStale(ANA, true))
                .thenThrow(new DomainException(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE, "PayPal is down"));
        IncomeSummaryService service = new IncomeSummaryService(mock(IncomeRepository.class), new FakeBusinessClock(TODAY), checks, refresher);

        assertThatThrownBy(() -> service.prepareIncomeForApplication(ANA)).isInstanceOf(DomainException.class);

        verify(checks, never()).grantConsent(any());
        verify(refresher, never()).store(any(), any());
    }

    @Test
    void freshStoredIncomeMeansNoReadAndNoWrite() {
        IncomeRefresher refresher = mock(IncomeRefresher.class);
        FairLoanCheckService checks = mock(FairLoanCheckService.class);
        when(refresher.fetchIfStale(ANA, true)).thenReturn(Optional.empty());
        IncomeSummaryService service = new IncomeSummaryService(mock(IncomeRepository.class), new FakeBusinessClock(TODAY), checks, refresher);

        service.prepareIncomeForApplication(ANA);
        service.prepareIncomeForApplication(ANA);

        verifyNoInteractions(checks);
        verify(refresher, never()).store(any(), any());
    }

    @Test
    void aSuccessfulReadRecordsTheConsentThenStoresTheWeeks() {
        IncomeRefresher refresher = mock(IncomeRefresher.class);
        FairLoanCheckService checks = mock(FairLoanCheckService.class);
        FetchedIncome fetched = new FetchedIncome(List.of(), TODAY, new FakeBusinessClock(TODAY).now());
        when(refresher.fetchIfStale(ANA, true)).thenReturn(Optional.of(fetched));
        IncomeSummaryService service = new IncomeSummaryService(mock(IncomeRepository.class), new FakeBusinessClock(TODAY), checks, refresher);

        service.prepareIncomeForApplication(ANA);

        InOrder order = inOrder(refresher, checks);
        order.verify(refresher).fetchIfStale(ANA, true);
        order.verify(checks).grantConsent(ANA);
        order.verify(refresher).store(ANA, fetched);
    }

    @Test
    void aRefreshOfAMemberWhoNeverConsentedIsAskedWithoutTheConsentFlagAndRecordsNoConsent() {
        IncomeRefresher refresher = mock(IncomeRefresher.class);
        FairLoanCheckService checks = mock(FairLoanCheckService.class);
        when(refresher.fetchIfStale(any(), anyBoolean())).thenReturn(Optional.empty());
        IncomeSummaryService service = new IncomeSummaryService(mock(IncomeRepository.class), new FakeBusinessClock(TODAY), checks, refresher);

        service.refreshIncomeIfConsented(ANA);

        verify(refresher).fetchIfStale(ANA, false);
        verifyNoInteractions(checks);
    }
}
