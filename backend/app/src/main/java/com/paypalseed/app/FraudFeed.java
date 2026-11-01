package com.paypalseed.app;

import com.paypalseed.fraud.api.FraudFacade;
import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.IdentityDtos.PayoutRecipient;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.api.events.IdentityEvents.MemberProfileChanged;
import com.paypalseed.identity.api.events.IdentityEvents.MemberRegistered;
import com.paypalseed.lending.api.events.LoanApplicationChanged;
import com.paypalseed.platform.kernel.BusinessClock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the fraud rules the three facts no fraud listener can import (ADR 0019, ADR 0021): an account being created, a
 * loan application, and a change of where a payout goes. Fraud already depends on lending, payments and savings, so
 * lending could never depend on it, and identity has no reason to; this wiring module may depend on all of them.
 *
 * <p>Facts are stamped with the business clock, like every fraud fact. Every handler is idempotent on its source key,
 * so the at-least-once delivery of events adds nothing twice. No payer id, email or name is logged or passed on in
 * clear: the destination is handed over as a reference that fraud hashes at once.
 */
@Component
class FraudFeed {

    private static final String SUBMITTED = "SUBMITTED";

    private final FraudFacade fraud;
    private final IdentityFacade identity;
    private final BusinessClock clock;

    FraudFeed(FraudFacade fraud, IdentityFacade identity, BusinessClock clock) {
        this.fraud = fraud;
        this.identity = identity;
        this.clock = clock;
    }

    /**
     * The account's real start, so {@code NEW_ACCOUNT_LOAN} no longer mistakes the first activity Seed saw for the
     * account's age. The payout destination at registration is the baseline later changes are compared with.
     */
    @ApplicationModuleListener
    void on(MemberRegistered e) {
        fraud.observe(new Observation(e.memberId(), FactKind.ACCOUNT_CREATED, e.occurredAt(), "account:" + e.memberId(), null, null,
                Map.of()));
        destination(e.memberId(), e.occurredAt(), "destination:" + e.eventId());
    }

    /**
     * The profile choices include the wallet payouts go to (PayPal wallet or Venmo). A change of language leaves the
     * destination as it was, which fraud sees and ignores; only a different destination becomes a fact.
     */
    @ApplicationModuleListener
    void on(MemberProfileChanged e) {
        destination(e.memberId(), e.occurredAt(), "destination:" + e.eventId());
    }

    /** Once per application, when it is submitted; later states of the same application are not new applications. */
    @ApplicationModuleListener
    void on(LoanApplicationChanged e) {
        if (SUBMITTED.equals(e.status())) {
            fraud.observe(new Observation(e.memberId(), FactKind.APPLICATION_SUBMITTED, clock.now(), "application:" + e.applicationId(),
                    e.requestedAmountCents(), null, Map.of()));
        }
    }

    private void destination(UUID memberId, Instant at, String sourceKey) {
        Optional<MemberView> member = identity.member(memberId);
        Optional<PayoutRecipient> recipient = identity.payoutRecipient(memberId);
        if (member.isEmpty() || recipient.isEmpty()) {
            return;
        }
        fraud.payoutDestination(memberId, reference(member.get().walletPreference(), recipient.get()), at, sourceKey);
    }

    /** What decides where money lands: the PayPal account for a wallet payout, the email for Venmo. */
    static String reference(WalletPreference preference, PayoutRecipient recipient) {
        WalletPreference wallet = preference == null ? WalletPreference.PAYPAL : preference;
        boolean venmoByEmail = wallet == WalletPreference.VENMO && recipient.email() != null && !recipient.email().isBlank();
        return wallet + ":" + (venmoByEmail ? recipient.email() : recipient.payerId());
    }
}
