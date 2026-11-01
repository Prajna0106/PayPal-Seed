package com.paypalseed.underwriting.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.paypalseed.platform.testing.FakeBusinessClock;
import com.paypalseed.underwriting.application.ports.IncomeAccountResolver;
import com.paypalseed.underwriting.application.ports.IncomeTransactionSource;
import com.paypalseed.underwriting.application.usecases.IncomeRefresher;
import com.paypalseed.underwriting.domain.model.IncomeEntry;
import com.paypalseed.underwriting.domain.model.IncomeSource;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.RefreshState;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IncomeRefresherTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private IncomeRepository income;
    private IncomeTransactionSource source;
    private IncomeAccountResolver accounts;
    private FakeBusinessClock clock;

    @BeforeEach
    void setUp() {
        income = mock(IncomeRepository.class);
        source = mock(IncomeTransactionSource.class);
        accounts = mock(IncomeAccountResolver.class);
        clock = new FakeBusinessClock(TODAY);
        when(accounts.accountOf(MEMBER)).thenReturn(Optional.of("PAYER-1"));
        when(income.consent(MEMBER)).thenReturn(Optional.of(new Consent(true, null)));
        when(income.refreshState(MEMBER)).thenReturn(Optional.empty());
    }

    private IncomeRefresher refresher(IncomeSource mode) {
        return new IncomeRefresher(income, source, accounts, clock, mode, Duration.ofHours(24));
    }

    @Test
    void seededModeNeverReadsPayPal() {
        assertThat(refresher(IncomeSource.SEEDED).refreshIfStale(MEMBER)).isFalse();

        verifyNoInteractions(source, accounts);
        verify(income, never()).replaceFetchedWeeks(any(), anyList(), any(), any());
    }

    @Test
    void withoutAnActiveConsentNothingIsFetched() {
        when(income.consent(MEMBER)).thenReturn(Optional.empty());
        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isFalse();

        when(income.consent(MEMBER)).thenReturn(Optional.of(new Consent(false, null)));
        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isFalse();

        verifyNoInteractions(source, accounts);
        verify(income, never()).replaceFetchedWeeks(any(), anyList(), any(), any());
    }

    @Test
    void weeksReadWithinTheMaxAgeAreNotReadAgain() {
        when(income.refreshState(MEMBER)).thenReturn(Optional.of(
                new RefreshState(clock.now().minus(Duration.ofHours(23)), TODAY)));

        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isFalse();

        verifyNoInteractions(source);
    }

    @Test
    void weeksOlderThanTheMaxAgeAreReadAgainAndTheExactBoundaryIsStillFresh() {
        when(income.refreshState(MEMBER)).thenReturn(Optional.of(
                new RefreshState(clock.now().minus(Duration.ofHours(24)), TODAY)));
        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isFalse();

        when(income.refreshState(MEMBER)).thenReturn(Optional.of(
                new RefreshState(clock.now().minus(Duration.ofHours(24)).minusSeconds(1), TODAY)));
        when(source.credits(anyString(), any(), any())).thenReturn(List.of());
        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isTrue();
    }

    @Test
    void aReadStampedInTheFutureIsTreatedAsStale() {
        when(income.refreshState(MEMBER)).thenReturn(Optional.of(
                new RefreshState(clock.now().plus(Duration.ofDays(3)), TODAY)));
        when(source.credits(anyString(), any(), any())).thenReturn(List.of());

        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isTrue();
    }

    @Test
    void seededWeeksWithoutAReadStampAreStale() {
        when(income.refreshState(MEMBER)).thenReturn(Optional.of(new RefreshState(null, null)));
        when(source.credits(anyString(), any(), any())).thenReturn(List.of());

        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isTrue();
    }

    @Test
    void aStaleSummaryIsReadOverTheWholeNinetyOneDaysInOneCallAndStoredAsThirteenWeeks() {
        when(source.credits(anyString(), any(), any())).thenReturn(List.of(
                new IncomeEntry("A", LocalDate.of(2026, 8, 11), 19_410),
                new IncomeEntry("D", LocalDate.of(2026, 11, 9), 50_000)));

        assertThat(refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER)).isTrue();

        // One range of 91 days: payments cuts it into the 31-day windows PayPal accepts, not this class.
        verify(source).credits("PAYER-1", LocalDate.of(2026, 8, 11), LocalDate.of(2026, 11, 9));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Week>> weeks = ArgumentCaptor.forClass(List.class);
        verify(income).replaceFetchedWeeks(eq(MEMBER), weeks.capture(), eq(TODAY), eq(clock.now()));
        assertThat(weeks.getValue()).hasSize(13);
        assertThat(weeks.getValue().get(0)).isEqualTo(new Week(LocalDate.of(2026, 8, 11), 19_410));
        assertThat(weeks.getValue().get(12)).isEqualTo(new Week(LocalDate.of(2026, 11, 3), 50_000));
    }

    @Test
    void aMemberWithoutALinkedAccountIsToldToLinkOneAndNothingIsFetched() {
        when(accounts.accountOf(MEMBER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(UnderwritingErrors.PAYPAL_ACCOUNT_NOT_LINKED));

        verifyNoInteractions(source);
        verify(income, never()).replaceFetchedWeeks(any(), anyList(), any(), any());
    }

    @Test
    void aSourceFailureRefusesWithARetryableProblemAndKeepsTheStoredWeeks() {
        when(source.credits(anyString(), any(), any()))
                .thenThrow(new DomainException(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE, "PayPal is down"));

        assertThatThrownBy(() -> refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE));
        assertThat(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE.kind()).isEqualTo(ErrorCode.Kind.UNAVAILABLE);

        verify(income, never()).replaceFetchedWeeks(any(), anyList(), any(), any());
    }

    @Test
    void anUnexpectedExceptionBecomesTheSameRetryableProblemWithoutLeakingItsMessage() {
        when(source.credits(anyString(), any(), any())).thenThrow(new IllegalStateException("secret internals"));

        assertThatThrownBy(() -> refresher(IncomeSource.TRANSACTION_SEARCH).refreshIfStale(MEMBER))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("secret");
                });
        verify(income, never()).replaceFetchedWeeks(any(), anyList(), any(), any());
    }

    @Test
    void verifiedMonthlyIncomesAreEmptyInSeededModeAndDoNotTouchPayPal() {
        assertThat(refresher(IncomeSource.SEEDED).verifiedMonthlyIncomes(MEMBER)).isEmpty();

        verifyNoInteractions(source, accounts);
    }

    @Test
    void aNonPositiveMaxAgeIsRejectedAtStartUp() {
        assertThatThrownBy(() -> new IncomeRefresher(income, source, accounts, clock, IncomeSource.SEEDED, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSourceSettingParsesBothSpellingsAndRejectsGuesses() {
        assertThat(IncomeSource.parse(null)).isEqualTo(IncomeSource.SEEDED);
        assertThat(IncomeSource.parse(" ")).isEqualTo(IncomeSource.SEEDED);
        assertThat(IncomeSource.parse("seeded")).isEqualTo(IncomeSource.SEEDED);
        assertThat(IncomeSource.parse("transaction-search")).isEqualTo(IncomeSource.TRANSACTION_SEARCH);
        assertThat(IncomeSource.parse("TRANSACTION_SEARCH")).isEqualTo(IncomeSource.TRANSACTION_SEARCH);
        assertThatThrownBy(() -> IncomeSource.parse("plaid")).isInstanceOf(IllegalArgumentException.class);
    }
}
