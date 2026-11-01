package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.payments.application.ports.PayPalException;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchTransactions;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reads PayPal Transaction Search and returns income credits. Transaction Search reports the account
 * the REST app belongs to. Reading a member's own wallet needs PayPal partner permissions, switched on
 * here with {@code seed.paypal.transaction-search.on-behalf}; without it the account reference is
 * ignored and the program account is read (the sandbox demo path).
 */
@Service
class IncomeService {

    /** PayPal keeps three years of history. */
    private static final long MAX_DAYS = 3 * 366;

    private final PayPalGateway paypal;
    private final boolean enabled;
    private final boolean onBehalf;

    IncomeService(PayPalGateway paypal,
                  @Value("${seed.paypal.transaction-search.enabled:false}") boolean enabled,
                  @Value("${seed.paypal.transaction-search.on-behalf:false}") boolean onBehalf) {
        this.paypal = paypal;
        this.enabled = enabled;
        this.onBehalf = onBehalf;
    }

    List<IncomeCredit> incomeTransactions(String accountReference, LocalDate from, LocalDate to) {
        if (!enabled) {
            throw new DomainException(PaymentsErrors.TRANSACTION_SEARCH_DISABLED,
                    "Set seed.paypal.transaction-search.enabled to read PayPal transaction history");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        if (to.toEpochDay() - from.toEpochDay() > MAX_DAYS) {
            throw new IllegalArgumentException("PayPal keeps three years of transaction history");
        }
        Instant start = from.atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant();
        Optional<String> asAccount = onBehalf && accountReference != null && !accountReference.isBlank()
                ? Optional.of(accountReference) : Optional.empty();
        List<SearchedTransaction> lines = new ArrayList<>();
        try {
            for (Instant[] window : IncomeNetting.windows(start, end)) {
                lines.addAll(paypal.searchTransactions(new SearchTransactions(window[0], window[1], asAccount)));
            }
        } catch (PayPalException e) {
            if (e.isTransient()) {
                throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
            }
            throw new DomainException(PaymentsErrors.PAYPAL_REJECTED, "PayPal refused the transaction search",
                    e.paypalIssue() == null ? Map.of() : Map.of("paypalIssue", e.paypalIssue()));
        }
        return IncomeNetting.credits(lines);
    }
}
