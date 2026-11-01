package com.paypalseed.underwriting.infrastructure.clients;

import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.application.ports.IncomeTransactionSource;
import com.paypalseed.underwriting.domain.model.IncomeEntry;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads income through {@link PaymentsFacade}, the only code allowed to talk to PayPal. The facade already cuts a
 * range into the 31-day windows PayPal accepts and nets fees and refunds; this adapter adds a total deadline so a
 * slow PayPal cannot hold a request (and a database connection above it) for several windows in a row.
 *
 * <p>The call runs on a virtual thread so the deadline can interrupt it. It does not use the caller's transaction,
 * which is fine because the facade call touches no database state of the caller.
 */
@Component
class PaymentsIncomeSource implements IncomeTransactionSource, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsIncomeSource.class);

    private final PaymentsFacade payments;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    PaymentsIncomeSource(PaymentsFacade payments, @Value("${seed.underwriting.income.timeout:30s}") Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("seed.underwriting.income.timeout must be positive");
        }
        this.payments = payments;
        this.timeout = timeout;
    }

    @Override
    public List<IncomeEntry> credits(
        String accountReference, LocalDate from, LocalDate to
    ) {
        Future<List<IncomeEntry>> call = executor.submit(
            () -> payments.incomeTransactions(accountReference, from, to).stream().map(PaymentsIncomeSource::map).toList());
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOG.warn("PayPal income read timed out after {}", timeout);
            throw unavailable("timeout");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String reason = cause instanceof DomainException d ? d.errorCode().code() : cause.getClass().getSimpleName();
            LOG.warn("PayPal income read failed: {}", reason);
            throw unavailable(reason);
        }
    }

    private static IncomeEntry map(IncomeCredit credit) {
        return new IncomeEntry(
            credit.transactionId(), credit.date(), credit.netCents());
    }

    private static DomainException unavailable(String reason) {
        return new DomainException(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE,
            "PayPal income could not be read; nothing was assessed. Try again shortly.",
            Map.of("retryable", true, "reason", reason));
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
