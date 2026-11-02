package com.paypalseed.app.home;

import com.paypalseed.hardship.api.HardshipFacade;
import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.lending.api.LendingFacade;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import com.paypalseed.savings.api.SavingsFacade;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The production {@link MemberReads}: each call goes to the owning module's public facade with the member id and
 * nothing else, and the answer is passed on as given. The facades return member-scoped read models that carry no risk
 * level, score or signal, so nothing here has to hold anything back.
 */
@Component
class FacadeMemberReads implements MemberReads {

    private final SavingsFacade savings;
    private final LendingFacade lending;
    private final HardshipFacade hardship;

    FacadeMemberReads(SavingsFacade savings, LendingFacade lending, HardshipFacade hardship) {
        this.savings = savings;
        this.lending = lending;
        this.hardship = hardship;
    }

    @Override
    public List<Goal> goals(UUID memberId) {
        return savings.goalsOfMember(id(memberId).toString()).stream()
            .map(g -> new Goal(g.goalId(), g.name(), g.status(), g.targetCents(), g.savedCents(), g.monthlyCents(),
                g.currency(), g.targetDate()))
            .toList();
    }

    @Override
    public EligibilityView eligibility(UUID memberId) {
        return lending.eligibilityOf(id(memberId));
    }

    @Override
    public List<MemberLoanView> loans(UUID memberId) {
        return lending.loansOfMember(id(memberId));
    }

    @Override
    public List<MemberApplicationView> applications(UUID memberId) {
        return lending.applicationsOfMember(id(memberId));
    }

    @Override
    public Optional<MemberCaseView> openHardshipCase(UUID memberId) {
        return hardship.openCaseOf(id(memberId));
    }

    private static UUID id(UUID memberId) {
        return Objects.requireNonNull(memberId, "memberId");
    }
}
