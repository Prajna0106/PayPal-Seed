package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps underwriting's income consent, the only one the fair check reads, in step with the member's consent ledger in
 * identity (ADR 0012: identity records, underwriting enforces). When the member withdraws the income-read consent
 * there, this closes the enforcing consent and forgets the weeks read from PayPal.
 */
@Service
public class IncomeConsentSync {

    private final IncomeRepository income;
    private final BusinessClock clock;

    public IncomeConsentSync(IncomeRepository income, BusinessClock clock) {
        this.income = income;
        this.clock = clock;
    }

    /**
     * Idempotent: a second delivery finds the consent closed and the weeks gone, and changes nothing. A member who
     * never gave underwriting a consent gets no row created by withdrawing one.
     */
    @Transactional
    public void incomeReadWithdrawn(UUID memberId) {
        if (income.consent(memberId).filter(Consent::active).isPresent()) {
            income.revoke(memberId, clock.now());
        }
        income.purgeFetched(memberId);
    }
}
