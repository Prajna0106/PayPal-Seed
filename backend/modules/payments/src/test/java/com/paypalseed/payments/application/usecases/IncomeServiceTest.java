package com.paypalseed.payments.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchTransactions;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomeServiceTest {

    private final List<SearchTransactions> searches = new ArrayList<>();

    private PayPalGateway gateway() {
        return (PayPalGateway) Proxy.newProxyInstance(PayPalGateway.class.getClassLoader(), new Class<?>[] {PayPalGateway.class},
                (proxy, method, args) -> {
                    if ("searchTransactions".equals(method.getName())) {
                        searches.add((SearchTransactions) args[0]);
                        return List.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void isRefusedWhileSwitchedOffAndNeverCallsPayPal() {
        IncomeService off = new IncomeService(gateway(), false, false);

        assertThatThrownBy(() -> off.incomeTransactions(null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PaymentsErrors.TRANSACTION_SEARCH_DISABLED));
        assertThat(searches).isEmpty();
    }

    @Test
    void readsOnBehalfOfTheMemberOnlyWhenPartnerReadingIsOn() {
        new IncomeService(gateway(), true, true).incomeTransactions("PAYER-9", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        new IncomeService(gateway(), true, true).incomeTransactions(" ", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));
        new IncomeService(gateway(), true, false).incomeTransactions("PAYER-9", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertThat(searches.stream().map(s -> s.onBehalfOfPayerId().orElse("own account")).toList())
                .containsExactly("PAYER-9", "own account", "own account");
    }

    @Test
    void refusesMoreHistoryThanPayPalKeeps() {
        IncomeService on = new IncomeService(gateway(), true, false);

        assertThatThrownBy(() -> on.incomeTransactions(null, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(searches).isEmpty();
    }
}
