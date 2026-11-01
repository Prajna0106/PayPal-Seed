package com.paypalseed.app.home;

import com.paypalseed.hardship.api.dto.HardshipDtos.MemberCaseView;
import com.paypalseed.lending.api.dto.LendingDtos.EligibilityView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.MemberLoanView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-member reads the Home page needs from the savings, lending and hardship facades (ADR 0018, ADR 0020). Each
 * facade answers by member id, only for that member. The production bean is {@link FacadeMemberReads}; if no bean
 * exists the sections that need it answer {@code unavailable} and the rest of the page is unaffected.
 */
public interface MemberReads {

    record Goal(UUID goalId, String name, String status, long targetCents, long savedCents, long monthlyCents,
                String currency, LocalDate targetDate) {
    }

    /** {@code SavingsFacade}: the member's goals, newest first. */
    List<Goal> goals(UUID memberId);

    /** {@code LendingFacade}: eligibility computed for the member by lending's own rules. */
    EligibilityView eligibility(UUID memberId);

    /** {@code LendingFacade}: the member's loans, any status, newest first. */
    List<MemberLoanView> loans(UUID memberId);

    /** {@code LendingFacade}: the member's applications, newest first. */
    List<MemberApplicationView> applications(UUID memberId);

    /** {@code HardshipFacade}: the member's case that is not finished and that the member may see, if any. */
    Optional<MemberCaseView> openHardshipCase(UUID memberId);
}
