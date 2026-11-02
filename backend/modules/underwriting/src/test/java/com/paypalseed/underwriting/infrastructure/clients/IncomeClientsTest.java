package com.paypalseed.underwriting.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.CounterpartyKind;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.domain.model.IncomeEntry;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomeClientsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 11);
    private static final LocalDate TO = LocalDate.of(2026, 11, 9);

    @Test
    void creditsAreMappedToNetAmountsAndTheRangeIsPassedThrough() {
        PaymentsFacade payments = mock(PaymentsFacade.class);
        when(payments.incomeTransactions("P1", FROM, TO)).thenReturn(List.of(
                new IncomeCredit("A", LocalDate.of(2026, 8, 12), 20_000, 590, 0, 19_410, CounterpartyKind.PERSON, "Gig")));
        PaymentsIncomeSource source = new PaymentsIncomeSource(payments, Duration.ofSeconds(5));

        assertThat(source.credits("P1", FROM, TO)).containsExactly(new IncomeEntry("A", LocalDate.of(2026, 8, 12), 19_410));
        source.destroy();
    }

    @Test
    void aSlowPayPalIsCutOffAtTheDeadline() {
        PaymentsFacade payments = mock(PaymentsFacade.class);
        when(payments.incomeTransactions(anyString(), any(), any())).thenAnswer(call -> {
            Thread.sleep(5_000);
            return List.of();
        });
        PaymentsIncomeSource source = new PaymentsIncomeSource(payments, Duration.ofMillis(100));

        long started = System.nanoTime();
        assertThatThrownBy(() -> source.credits("P1", FROM, TO)).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE);
            assertThat(e.details()).containsEntry("retryable", true).containsEntry("reason", "timeout");
        });
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        source.destroy();
    }

    @Test
    void anyPaymentsFailureIsReportedAsOneRetryableProblemNamingTheCause() {
        PaymentsFacade payments = mock(PaymentsFacade.class);
        when(payments.incomeTransactions(anyString(), any(), any()))
                .thenThrow(new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again"))
                .thenThrow(new IllegalStateException("boom"));
        PaymentsIncomeSource source = new PaymentsIncomeSource(payments, Duration.ofSeconds(5));

        assertThatThrownBy(() -> source.credits("P1", FROM, TO)).isInstanceOfSatisfying(DomainException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE);
            assertThat(e.details()).containsEntry("reason", "dependency-unavailable");
        });
        assertThatThrownBy(() -> source.credits("P1", FROM, TO)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.details()).containsEntry("reason", "IllegalStateException"));
        source.destroy();
    }

    @Test
    void aNonPositiveTimeoutIsRejected() {
        assertThatThrownBy(() -> new PaymentsIncomeSource(mock(PaymentsFacade.class), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMemberIsLinkedWhenIdentityKnowsThem() {
        IdentityFacade identity = mock(IdentityFacade.class);
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(identity.member(known)).thenReturn(Optional.of(mock(MemberView.class)));
        when(identity.member(unknown)).thenReturn(Optional.empty());
        IdentityIncomeAccountResolver resolver = new IdentityIncomeAccountResolver(identity);

        assertThat(resolver.accountOf(known)).isPresent();
        assertThat(resolver.accountOf(unknown)).isEmpty();
    }
}
