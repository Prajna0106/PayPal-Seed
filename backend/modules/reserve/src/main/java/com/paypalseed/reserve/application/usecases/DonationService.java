package com.paypalseed.reserve.application.usecases;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.AccountType;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationIntent;
import com.paypalseed.reserve.api.dto.ReserveDtos.DonationReceipt;
import com.paypalseed.reserve.api.events.ReserveEvents.DonationReceived;
import com.paypalseed.reserve.domain.model.DonationCustomId;
import com.paypalseed.reserve.domain.model.DonorName;
import com.paypalseed.reserve.domain.model.FundState;
import com.paypalseed.reserve.domain.model.RequestHashes;
import com.paypalseed.reserve.domain.model.ReserveErrors;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository.DonationRow;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository.IntentRow;
import com.paypalseed.reserve.infrastructure.persistence.FundRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gifts to the fund. A capture is recorded once, with its ledger entry and the fund counters in the same
 * transaction (ADR 0002). The donor is anonymous unless they chose to be shown; nothing else about them is kept.
 */
@Service
public class DonationService {

    private static final int MAX_KEY_LENGTH = 128;

    private final DonationRepository donations;
    private final FundRepository funds;
    private final LedgerFacade ledger;
    private final ReserveSettings settings;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    public DonationService(DonationRepository donations, FundRepository funds, LedgerFacade ledger, ReserveSettings settings,
                           BusinessClock clock, ApplicationEventPublisher events) {
        this.donations = donations;
        this.funds = funds;
        this.ledger = ledger;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    /** MANDATORY: the ledger entry commits with the caller's state change or not at all. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DonationReceipt record(String captureId, long amountCents, long feeCents, String donorDisplayName, boolean anonymous) {
        if (captureId == null || captureId.isBlank() || captureId.length() > MAX_KEY_LENGTH) {
            throw new DomainException(ReserveErrors.DONATION_AMOUNT_INVALID, "A capture id of 1 to 128 characters is required");
        }
        if (amountCents <= 0 || amountCents > settings.maxDonationCents() || feeCents < 0 || feeCents >= amountCents) {
            throw new DomainException(ReserveErrors.DONATION_AMOUNT_INVALID,
                    "A donation is between 1 cent and " + settings.maxDonationCents() + " cents, and the fee is less than the gift");
        }
        // The fund row is the lock every money movement starts with, so two deliveries of one capture queue up here.
        FundState fund = funds.lock();
        Optional<DonationRow> existing = donations.byCapture(captureId);
        if (existing.isPresent()) {
            DonationRow first = existing.get();
            if (first.amountCents() != amountCents) {
                throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED,
                        "Capture " + captureId + " was recorded with another amount");
            }
            return new DonationReceipt(first.id(), captureId, first.amountCents(), true);
        }
        Optional<String> shown = anonymous ? Optional.empty() : DonorName.clean(donorDisplayName);
        LocalDate today = clock.today();
        UUID id = Ids.newId();
        UUID entry = ledger.post(PostingRequest.builder("reserve-donation:" + captureId, "Reserve donation", today)
                .debit(AccountRef.program(AccountType.PROGRAM_CASH), amountCents - feeCents)
                .debit(AccountRef.program(AccountType.PAYPAL_FEES), feeCents)
                .credit(AccountRef.program(AccountType.LOSS_RESERVE), amountCents)
                .build());
        donations.insert(new DonationRow(id, captureId, amountCents, feeCents, shown.isEmpty(), shown.orElse(null), entry, today));
        funds.save(fund.donate(amountCents));
        events.publishEvent(new DonationReceived(Ids.newId(), id, captureId, amountCents, today));
        return new DonationReceipt(id, captureId, amountCents, false);
    }

    /** Resolves the donor's choices from the intent the PayPal custom id names; unknown or malformed means anonymous. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DonationReceipt recordForCustomId(String customId, String captureId, long amountCents, long feeCents) {
        Optional<IntentRow> intent = DonationCustomId.parse(customId).flatMap(donations::intent);
        return record(captureId, amountCents, feeCents, intent.map(IntentRow::displayName).orElse(null),
                intent.map(IntentRow::anonymous).orElse(true));
    }

    @Transactional
    public DonationIntent createIntent(String idempotencyKey, boolean anonymous, String displayName) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        String hash = RequestHashes.of(anonymous, displayName);
        Optional<String> shown = anonymous ? Optional.empty() : DonorName.clean(displayName);
        // A donor who asked to be shown but gave no usable name is simply anonymous.
        IntentRow fresh = new IntentRow(Ids.newId(), idempotencyKey, hash, shown.isEmpty(), shown.orElse(null));
        IntentRow stored = donations.insertIntent(fresh) ? fresh : donations.intentByKey(idempotencyKey).orElseThrow();
        if (!stored.requestHash().equals(hash)) {
            throw new DomainException(CommonErrors.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was used with other choices");
        }
        return new DonationIntent(stored.id(), DonationCustomId.format(stored.id()));
    }
}
