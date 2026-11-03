package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.underwriting.application.ports.MemberConsentLedger;
import com.paypalseed.underwriting.application.ports.MemberConsentLedger.Line;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps underwriting's income consent, the only one the fair check reads, in step with the member's consent ledger in
 * identity (ADR 0012: identity records, underwriting enforces). When the member withdraws the income-read consent
 * there, this closes the enforcing consent and forgets the weeks read from PayPal.
 *
 * <p>The enforcing consent is one record behind two lines of the member's list, {@code INCOME_READ} and
 * {@code FAIR_CHECK} (ADR 0026). Withdrawing either line ends it, and the other line is closed too, so the list never
 * shows a consent that nothing honours.
 */
@Service
public class IncomeConsentSync {

    private final IncomeRepository income;
    private final BusinessClock clock;
    private final MemberConsentLedger ledger;

    public IncomeConsentSync(IncomeRepository income, BusinessClock clock) {
        this(income, clock, null);
    }

    @Autowired
    public IncomeConsentSync(IncomeRepository income, BusinessClock clock,
                             @Autowired(required = false) MemberConsentLedger ledger) {
        this.income = income;
        this.clock = clock;
        this.ledger = ledger;
    }

    /**
     * Idempotent: a second delivery finds the consent closed and the weeks gone, and changes nothing. A member who
     * never gave underwriting a consent gets no row created by withdrawing one.
     */
    @Transactional
    public void incomeReadWithdrawn(UUID memberId) {
        withdrawn(memberId, Line.INCOME_READ);
    }

    /** The member took the fair-check line off their list; same effect and same idempotency as the income line. */
    @Transactional
    public void fairCheckWithdrawn(UUID memberId) {
        withdrawn(memberId, Line.FAIR_CHECK);
    }

    private void withdrawn(UUID memberId, Line line) {
        if (ledger != null && !ledger.withdrawn(memberId, line)) {
            return;
        }
        if (income.consent(memberId).filter(Consent::active).isPresent()) {
            income.revoke(memberId, clock.now());
        }
        income.purgeFetched(memberId);
    }
}
