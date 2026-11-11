package com.paypalseed.app;

import com.paypalseed.creditrecord.api.TrustRecordFigures;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.savings.api.SavingsFacade;
import com.paypalseed.savings.api.dto.MemberSavingsSummary;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Gives the trust record the money figures it does not keep (ADR 0014: the record holds counts and dates only and imports
 * no savings or lending class). Each call goes to the owning facade with the one member's id, so nothing of another member
 * can be read here.
 *
 * <p>Repaid counts what the member paid on loans that stand. A loan that never stood (cancelled in the cooling-off window,
 * or whose payout came back, ADR 0024) adds nothing, and neither does the part of an instalment the safety fund paid
 * (ADR 0020): that is help, not the member's repayment.
 */
@Component
class TrustRecordFiguresAdapter implements TrustRecordFigures {

    private static final Set<String> NEVER_STOOD = Set.of("CANCELLED", "DISBURSEMENT_RETURNED");

    private final SavingsFacade savings;
    private final LendingFacade lending;

    TrustRecordFiguresAdapter(SavingsFacade savings, LendingFacade lending) {
        this.savings = savings;
        this.lending = lending;
    }

    @Override
    public MemberFigures of(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId");
        MemberSavingsSummary saved = savings.getMemberSavingsSummary(memberId.toString());
        long repaid = 0;
        for (MemberLoanView loan : lending.loansOfMember(memberId)) {
            if (NEVER_STOOD.contains(loan.status())) {
                continue;
            }
            for (InstalmentLine line : lending.currentSchedule(loan.loanId()).instalments()) {
                repaid += line.paidCents() - line.reserveCoveredCents();
            }
        }
        return new MemberFigures(Math.max(0, saved.currentStreakMonths()), Math.max(0, saved.totalSavedBalanceCents()),
            Math.max(0, repaid));
    }
}
