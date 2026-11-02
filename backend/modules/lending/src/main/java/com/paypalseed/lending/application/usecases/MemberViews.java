package com.paypalseed.lending.application.usecases;

import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.InstalmentLine;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.lending.api.dto.LendingDtos.NextDue;
import com.paypalseed.lending.domain.model.LoanRow;
import com.paypalseed.lending.domain.model.LoanRow.CollectionMode;
import com.paypalseed.lending.infrastructure.persistence.LoanRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What one member may see of their own lending, for the Home page and any other per-member read. Every method takes
 * the member id and returns only that member's rows, so there is no way to ask for someone else's. Nothing here
 * writes, and nothing carries a days-past-due figure, a bucket, an officer id or a sizing reason.
 */
@Service
@Transactional(readOnly = true)
public class MemberViews {

    private final LoanRepository loans;
    private final LoanQueries queries;
    private final MicroloanApplicationService applications;

    public MemberViews(LoanRepository loans, LoanQueries queries, MicroloanApplicationService applications) {
        this.loans = loans;
        this.queries = queries;
        this.applications = applications;
    }

    public EligibilityView eligibility(UUID memberId) {
        return applications.checkEligibility(memberId, OptionalLong.empty());
    }

    /** Newest disbursement first. */
    public List<MemberLoanView> loans(UUID memberId) {
        return loans.byMember(memberId).stream()
                .sorted(Comparator.comparing(LoanRow::disbursedOn).thenComparing(LoanRow::id).reversed())
                .map(this::view)
                .toList();
    }

    /** Newest first. */
    public List<MemberApplicationView> applications(UUID memberId) {
        return applications.listMemberApplications(memberId).stream()
                .sorted(Comparator.comparing(LoanApplicationView::createdAt).reversed())
                .map(a -> new MemberApplicationView(a.id(), a.status(), a.requestedAmountCents(), a.termMonths(),
                        a.coolingOffExpiresAt(), a.payoutIssue()))
                .toList();
    }

    private MemberLoanView view(LoanRow loan) {
        NextDue next = loan.isActive() ? nextDue(loan) : null;
        return new MemberLoanView(loan.id(), loan.status().name(), loan.principalCents(), loan.outstandingCents(), next,
                loan.collectionMode() == CollectionMode.AUTOPAY);
    }

    private NextDue nextDue(LoanRow loan) {
        return queries.schedule(loan, Optional.empty()).instalments().stream()
                .filter(line -> line.remainingCents() > 0)
                .min(Comparator.comparingInt(InstalmentLine::seq))
                .map(line -> new NextDue(line.seq(), line.dueDate(), line.amountCents(), line.remainingCents()))
                .orElse(null);
    }
}
