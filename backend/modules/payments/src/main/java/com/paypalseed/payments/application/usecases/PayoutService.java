package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutStatus;
import com.paypalseed.payments.api.dto.PaymentsDtos.PayoutView;
import com.paypalseed.payments.api.dto.PaymentsDtos.SendPayout;
import com.paypalseed.payments.application.ports.PayPalGateway;
import com.paypalseed.payments.application.ports.PayPalGateway.CreatePayout;
import com.paypalseed.payments.application.ports.PayPalGateway.PayoutItem;
import com.paypalseed.payments.application.usecases.PayoutFacts.Report;
import com.paypalseed.payments.domain.model.PaymentsErrors;
import com.paypalseed.payments.infrastructure.persistence.InboxRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository;
import com.paypalseed.payments.infrastructure.persistence.PayoutRepository.Payout;
import com.paypalseed.platform.kernel.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends money out through PayPal Payouts, one item per batch. The caller's {@code referenceId} is the
 * idempotency key everywhere: it names the payout row, seeds PayPal's {@code sender_batch_id} and
 * {@code PayPal-Request-Id}, and so a replay can never pay twice.
 */
@Service
class PayoutService {

    /** Marks batches Seed sent, so webhooks for payouts sent from the same PayPal account by others are ignored. */
    static final String SENDER_PREFIX = "seed:";

    private final PayPalGateway paypal;
    private final PayPalCommandExecutor executor;
    private final PayoutRepository payouts;
    private final PayoutFacts facts;
    private final InboxRepository inbox;

    PayoutService(PayPalGateway paypal, PayPalCommandExecutor executor, PayoutRepository payouts, PayoutFacts facts,
                  InboxRepository inbox) {
        this.paypal = paypal;
        this.executor = executor;
        this.payouts = payouts;
        this.facts = facts;
        this.inbox = inbox;
    }

    @Transactional
    PayoutView send(SendPayout c) {
        Optional<Payout> existing = payouts.byReferenceForUpdate(c.referenceId());
        if (existing.isPresent()) {
            return replay(existing.get(), c);
        }
        // A deterministic id keeps the command log and the payout row aligned if this transaction is retried.
        UUID id = UUID.nameUUIDFromBytes(("payout:" + c.referenceId()).getBytes(StandardCharsets.UTF_8));
        Payout fresh = new Payout(id, c.referenceId(), SENDER_PREFIX + c.referenceId(), c.amountCents(), c.recipientType(),
                c.recipient(), c.wallet(), c.note(), PayoutStatus.REQUESTED, null, null, null, 0, null);
        if (!payouts.insert(fresh)) {
            return replay(payouts.byReferenceForUpdate(c.referenceId()).orElseThrow(), c);
        }
        payouts.addTransition(id, null, PayoutStatus.REQUESTED, null, "API", null);
        inbox.wake("payout:" + fresh.senderBatchId());

        CreatePayout request = new CreatePayout(fresh.senderBatchId(), c.referenceId(), c.recipientType(), c.recipient(),
                c.wallet(), c.amountCents(), c.note());
        PayoutItem result;
        try {
            result = executor.run("CREATE_PAYOUT", id, "payout:" + c.referenceId(), request, PayoutItem.class,
                    rid -> paypal.createPayout(request, rid));
        } catch (DomainException e) {
            if (e.errorCode() != PaymentsErrors.PAYPAL_REJECTED) {
                throw e; // transient: nothing is recorded, the caller retries the same reference
            }
            // PayPal refused: the payout failed for good and the reference is spent; a new attempt needs a new reference.
            Object issue = e.details().getOrDefault("paypalIssue", "REJECTED");
            return view(facts.apply(fresh, new Report(Optional.of(PayoutStatus.FAILED), null, null, null, 0,
                    String.valueOf(issue), "API", null)));
        }
        return view(facts.apply(fresh, Report.of(result, "API", null)));
    }

    @Transactional(readOnly = true)
    Optional<PayoutView> find(String referenceId) {
        return payouts.byReference(referenceId).map(PayoutService::view);
    }

    private static PayoutView replay(Payout existing, SendPayout c) {
        boolean same = existing.amountCents() == c.amountCents() && existing.recipientType() == c.recipientType()
                && existing.receiver().equals(c.recipient()) && existing.wallet() == c.wallet();
        if (!same) {
            throw new DomainException(PaymentsErrors.PAYOUT_REFERENCE_REUSED,
                    "Payout reference " + c.referenceId() + " was already used for a different amount or recipient");
        }
        return view(existing);
    }

    static PayoutView view(Payout p) {
        return new PayoutView(p.id(), p.referenceId(), p.status(), p.amountCents(), p.wallet(),
                Optional.ofNullable(p.paypalBatchId()), Optional.ofNullable(p.paypalItemId()),
                Optional.ofNullable(p.paypalTransactionId()), p.feeCents(), Optional.ofNullable(p.failureReason()));
    }
}
