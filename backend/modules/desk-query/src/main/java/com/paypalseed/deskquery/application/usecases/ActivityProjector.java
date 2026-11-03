package com.paypalseed.deskquery.application.usecases;

import com.paypalseed.deskquery.domain.model.MemberRefs;
import com.paypalseed.deskquery.infrastructure.persistence.ActivityRepository;
import com.paypalseed.lending.api.events.LendingEvents.InstalmentView;
import com.paypalseed.lending.api.events.LendingEvents.LoanVoided;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReceived;
import com.paypalseed.lending.api.events.LendingEvents.RepaymentReversed;
import com.paypalseed.lending.api.events.LendingEvents.ScheduleGenerated;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.api.events.WithdrawalReturnedEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's own activity list, from facts other modules publish. Each row is keyed by the fact it describes, never by
 * the event that carried it, so a repeated delivery, a replay of the publication log and the rebuild of deposits from the
 * money itself all land on the same row. A row holds a kind, whole cents, a time and the goal or loan; it holds no PayPal
 * reference and nothing about hardship (a case, a plan or a score is not a thing the member did).
 *
 * <p>Lending events carry a business date and no instant, so such a row is dated at the start of that business day in
 * {@link BusinessClock#PROGRAM_ZONE}: the same input always gives the same time, which a replay needs.
 */
@Service
public class ActivityProjector {

    /** Lending names the safety fund as the source of a repayment the fund paid. */
    static final String RESERVE_SOURCE = "RESERVE";

    private final ActivityRepository activity;

    ActivityProjector(ActivityRepository activity) {
        this.activity = activity;
    }

    /** The key a deposit has whichever way it arrives: its PayPal transaction. */
    public static String depositKey(String paypalTxnId) {
        return "deposit:" + paypalTxnId;
    }

    @Transactional
    public void on(DepositReceivedEvent e) {
        String id = e.payPalCaptureId() == null ? e.eventId().toString() : e.payPalCaptureId();
        deposit(depositKey(id), e.memberId(), e.goalId(), e.amount().cents(), e.timestamp());
    }

    /** Also the entry of the rebuild, which reads the deposits from savings. */
    @Transactional
    public void deposit(String key, String savingsMemberId, UUID goalId, long amountCents, Instant at) {
        activity.record(key, MemberRefs.ofSavings(savingsMemberId), "DEPOSIT", amountCents, at, goalId, null);
    }

    @Transactional
    public void on(WithdrawalProcessedEvent e) {
        activity.record("withdrawal:" + e.eventId(), MemberRefs.ofSavings(e.memberId()), "WITHDRAWAL", e.amount().cents(),
                e.timestamp(), e.goalId(), null);
    }

    @Transactional
    public void on(WithdrawalReturnedEvent e) {
        activity.record("withdrawal-returned:" + e.withdrawalId(), MemberRefs.ofSavings(e.memberId()), "WITHDRAWAL_RETURNED",
                e.amount().cents(), e.timestamp(), e.goalId(), null);
    }

    /** Version 1 is the loan being paid out; a later version is a new plan for the same loan and says nothing new. */
    @Transactional
    public void on(ScheduleGenerated e) {
        if (e.version() != 1) {
            return;
        }
        long principal = e.instalments().stream().mapToLong(InstalmentView::amountCents).sum();
        activity.record("loan-disbursed:" + e.loanId(), MemberRefs.ofSavings(e.memberId().toString()), "LOAN_DISBURSED",
                principal, startOf(e.businessDate()), null, e.loanId());
    }

    @Transactional
    public void on(RepaymentReceived e) {
        boolean cover = RESERVE_SOURCE.equals(e.source());
        activity.record("repayment:" + e.transactionId(), MemberRefs.ofSavings(e.memberId().toString()),
                cover ? "COVER_APPLIED" : "LOAN_PAYMENT", cover ? e.appliedCents() : e.amountCents(), startOf(e.businessDate()),
                null, e.loanId());
    }

    @Transactional
    public void on(RepaymentReversed e) {
        activity.record("repayment-reversed:" + e.transactionId(), MemberRefs.ofSavings(e.memberId().toString()),
                "LOAN_PAYMENT_REVERSED", e.amountCents(), startOf(e.businessDate()), null, e.loanId());
    }

    @Transactional
    public void on(LoanVoided e) {
        activity.record("loan-voided:" + e.loanId(), MemberRefs.ofSavings(e.memberId().toString()), "LOAN_VOIDED",
                e.principalCents(), startOf(e.businessDate()), null, e.loanId());
    }

    private static Instant startOf(LocalDate businessDate) {
        return businessDate.atStartOfDay(BusinessClock.PROGRAM_ZONE).toInstant();
    }
}
