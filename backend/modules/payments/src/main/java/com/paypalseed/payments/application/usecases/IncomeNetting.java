package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.CounterpartyKind;
import com.paypalseed.payments.api.dto.PaymentsDtos.IncomeCredit;
import com.paypalseed.payments.application.ports.PayPalGateway.SearchedTransaction;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns raw Transaction Search lines into income. A credit is a successful payment received (event codes
 * {@code T00xx}); fees are the negative {@code fee_amount} on the same line; a refund, reversal or
 * chargeback ({@code T11xx}, {@code T12xx}) is a separate negative line that points at the payment it
 * undoes through {@code paypal_reference_id}. A refund whose payment lies outside the window cannot be
 * matched and is left out, which is why callers should read generously wide windows.
 */
final class IncomeNetting {

    /** Transaction Search accepts at most 31 days per call. */
    static final Duration MAX_WINDOW = Duration.ofDays(31);

    private IncomeNetting() {
    }

    /** Consecutive windows of at most 31 days covering {@code [from, to)}. */
    static List<Instant[]> windows(Instant from, Instant to) {
        List<Instant[]> out = new ArrayList<>();
        Instant start = from;
        while (start.isBefore(to)) {
            Instant end = start.plus(MAX_WINDOW).isBefore(to) ? start.plus(MAX_WINDOW) : to;
            out.add(new Instant[] {start, end});
            start = end;
        }
        return out;
    }

    /** Oldest first. A line seen in two windows (the boundary second) counts once. */
    static List<IncomeCredit> credits(List<SearchedTransaction> lines) {
        Map<String, SearchedTransaction> byId = new LinkedHashMap<>();
        for (SearchedTransaction t : lines) {
            byId.putIfAbsent(t.transactionId(), t);
        }
        Map<String, Long> refunded = new HashMap<>();
        for (SearchedTransaction t : byId.values()) {
            if (isSettled(t) && t.amountCents() < 0 && isUndo(t.eventCode()) && t.referenceTransactionId().isPresent()) {
                refunded.merge(t.referenceTransactionId().get(), -t.amountCents(), Long::sum);
            }
        }
        List<IncomeCredit> out = new ArrayList<>();
        for (SearchedTransaction t : byId.values()) {
            if (!isSettled(t) || t.amountCents() <= 0 || !isPaymentReceived(t.eventCode())) {
                continue;
            }
            long gross = t.amountCents();
            long fee = Math.abs(t.feeCents());
            long back = Math.min(gross, refunded.getOrDefault(t.transactionId(), 0L));
            out.add(new IncomeCredit(t.transactionId(), BusinessClock.businessDateOf(t.initiatedAt()), gross, fee, back,
                    Math.max(0, gross - fee - back), counterparty(t), t.subject()));
        }
        out.sort(Comparator.comparing(IncomeCredit::date).thenComparing(IncomeCredit::transactionId));
        return out;
    }

    private static boolean isSettled(SearchedTransaction t) {
        return "S".equals(t.status());
    }

    private static boolean isPaymentReceived(String code) {
        return code.startsWith("T00");
    }

    private static boolean isUndo(String code) {
        return code.startsWith("T11") || code.startsWith("T12");
    }

    private static CounterpartyKind counterparty(SearchedTransaction t) {
        if ("T0001".equals(t.eventCode())) {
            return CounterpartyKind.PAYOUT;
        }
        if ("T0002".equals(t.eventCode()) || "T0003".equals(t.eventCode())) {
            return CounterpartyKind.SUBSCRIPTION;
        }
        if (t.payerIsPerson()) {
            return CounterpartyKind.PERSON;
        }
        return t.payerAccountId().isPresent() ? CounterpartyKind.BUSINESS : CounterpartyKind.UNKNOWN;
    }
}
