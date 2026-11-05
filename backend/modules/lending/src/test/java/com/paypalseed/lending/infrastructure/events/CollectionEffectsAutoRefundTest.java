package com.paypalseed.lending.infrastructure.events;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.application.usecases.HeldRefunds;
import com.paypalseed.lending.application.usecases.LoanQueries;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.LoanStatus;
import com.paypalseed.payments.api.PaymentsFacade;
import com.paypalseed.payments.api.dto.PaymentsDtos.RefundOverpayment;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** FR-08 AC2: an overpayment PayPal cannot refund yet becomes a held refund (retry, then FAILED and audit), not a log line. */
class CollectionEffectsAutoRefundTest {

    private final PaymentsFacade payments = mock(PaymentsFacade.class);
    private final LoanQueries queries = mock(LoanQueries.class);
    private final HeldRefunds heldRefunds = mock(HeldRefunds.class);
    private final CollectionEffects effects = new CollectionEffects(payments, queries, heldRefunds);

    private final UUID loanId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();

    private RepaymentReceived received() {
        return new RepaymentReceived(UUID.randomUUID(), loanId, memberId, LocalDate.of(2026, 12, 9), transactionId, "PAYPAL",
                125_000, 120_000, 5_000, 0, "REPAID");
    }

    private void repaidLoanHolding(long overpaid) {
        LoanRow loan = mock(LoanRow.class);
        when(loan.id()).thenReturn(loanId);
        when(loan.memberId()).thenReturn(memberId);
        when(loan.status()).thenReturn(LoanStatus.REPAID);
        when(loan.overpaidCents()).thenReturn(overpaid);
        when(queries.loan(loanId)).thenReturn(loan);
    }

    @Test
    void aRefundPayPalHasTooLittleForIsKeptAsAHeldRefund() {
        repaidLoanHolding(5_000);
        when(payments.refundableCents(loanId)).thenReturn(2_000L);

        effects.on(received());

        verify(heldRefunds).owe(loanId, memberId, "auto-refund:" + transactionId, 5_000);
        verify(payments, never()).refundOverpayment(any());
    }

    @Test
    void aRefundPayPalCanMakeIsMadeAtOnceAndNotHeld() {
        repaidLoanHolding(5_000);
        when(payments.refundableCents(loanId)).thenReturn(125_000L);

        effects.on(received());

        verify(payments).refundOverpayment(new RefundOverpayment(loanId, 5_000, "auto-refund:" + transactionId));
        verify(heldRefunds, never()).owe(any(), any(), anyString(), anyLong());
    }
}
