package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentView;
import com.paypalseed.identity.api.dto.IdentityDtos.GrantResult;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentGranted;
import com.paypalseed.identity.api.events.IdentityEvents.ConsentRevoked;
import com.paypalseed.identity.domain.model.ConsentEntry;
import com.paypalseed.identity.domain.model.ConsentLedger;
import com.paypalseed.identity.domain.model.ConsentLedger.Step;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.identity.infrastructure.persistence.ConsentRepository;
import com.paypalseed.identity.infrastructure.persistence.MemberRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member-facing consent ledger. It records what a member agreed to; the module that acts on a consent (for example
 * underwriting for income read) still enforces its own copy, and reacts to {@code ConsentRevoked} to stay in step.
 */
@Service
public class ConsentService {

    private final MemberRepository members;
    private final ConsentRepository consents;
    private final ConsentCatalog catalog;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    ConsentService(MemberRepository members, ConsentRepository consents, ConsentCatalog catalog, BusinessClock clock,
                   ApplicationEventPublisher events) {
        this.members = members;
        this.consents = consents;
        this.catalog = catalog;
        this.clock = clock;
        this.events = events;
    }

    /** Idempotent: the same type and version while active changes nothing. */
    @Transactional
    public GrantResult grant(UUID memberId, ConsentGrant grant) {
        lockMember(memberId);
        ConsentType type = grant.type();
        String current = catalog.currentVersion(type);
        String version = grant.version() == null || grant.version().isBlank() ? current : grant.version().trim();
        if (!version.equals(current)) {
            throw new DomainException(IdentityErrors.CONSENT_VERSION_STALE, "The current " + type + " text is version " + current,
                    Map.of("type", type.name(), "currentVersion", current));
        }

        ConsentLedger ledger = new ConsentLedger(consents.forMember(memberId));
        Step step = ledger.stepForGrant(type, version);
        if (step == Step.ALREADY_ACTIVE) {
            return new GrantResult(false, view(ledger.active(type).orElseThrow()));
        }
        Instant now = now();
        if (step == Step.CLOSE_AND_APPEND) {
            ConsentEntry older = ledger.active(type).orElseThrow();
            consents.close(older.id(), older.closedAt(now).revokedAt());
        }
        ConsentEntry added = new ConsentEntry(Ids.newId(), memberId, type, version, now, null);
        consents.append(added);
        events.publishEvent(new ConsentGranted(Ids.newId(), memberId, added.id(), type, version, now));
        return new GrantResult(true, view(added));
    }

    /** Always allowed and immediate. True when an active line was closed. */
    @Transactional
    public boolean revoke(UUID memberId, ConsentType type) {
        lockMember(memberId);
        ConsentLedger ledger = new ConsentLedger(consents.forMember(memberId));
        ConsentEntry open = ledger.active(type).orElse(null);
        if (open == null) {
            return false;
        }
        Instant now = now();
        ConsentEntry closed = open.closedAt(now);
        consents.close(open.id(), closed.revokedAt());
        events.publishEvent(new ConsentRevoked(Ids.newId(), memberId, open.id(), type, open.version(), closed.revokedAt()));
        return true;
    }

    @Transactional(readOnly = true)
    public List<ConsentView> list(UUID memberId) {
        members.find(memberId).orElseThrow(MemberService::notFound);
        return consents.forMember(memberId).stream().map(ConsentService::view).toList();
    }

    @Transactional(readOnly = true)
    public boolean hasActive(UUID memberId, ConsentType type) {
        return consents.hasActive(memberId, type);
    }

    /** Serialises ledger changes of one member and answers 404 for a member that has no record. */
    private void lockMember(UUID memberId) {
        members.lock(memberId).orElseThrow(MemberService::notFound);
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static ConsentView view(ConsentEntry e) {
        return new ConsentView(e.id(), e.type(), e.version(), e.active(), e.grantedAt(), e.revokedAt());
    }
}
