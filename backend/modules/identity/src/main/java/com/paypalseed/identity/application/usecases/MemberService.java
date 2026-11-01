package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.api.dto.IdentityDtos.LoginResult;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.dto.IdentityDtos.PayoutRecipient;
import com.paypalseed.identity.api.dto.IdentityDtos.ProfileUpdate;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.events.IdentityEvents.MemberProfileChanged;
import com.paypalseed.identity.api.events.IdentityEvents.MemberRegistered;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.identity.domain.model.Member;
import com.paypalseed.identity.infrastructure.persistence.MemberRepository;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.Ids;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Members: the payer id to member mapping, and the profile a member edits. */
@Service
public class MemberService {

    private final MemberRepository members;
    private final BusinessClock clock;
    private final ApplicationEventPublisher events;

    MemberService(MemberRepository members, BusinessClock clock, ApplicationEventPublisher events) {
        this.members = members;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Idempotent on the payer id. The existing row is locked first, so two logins of one person cannot both create a
     * member; if a concurrent first login wins the insert, this one continues as a later login.
     */
    @Transactional
    public LoginResult login(PayPalLogin login) {
        String payerId = Member.requirePayerId(login.paypalPayerId());
        Instant now = now();
        Language provided = Language.fromLocale(login.locale()).orElse(null);

        Optional<Member> existing = members.lockByPayer(payerId);
        if (existing.isEmpty()) {
            Member fresh = Member.register(Ids.newId(), payerId, login.email(), login.name(), login.verified(), provided, now);
            if (members.insertIfAbsent(fresh)) {
                events.publishEvent(new MemberRegistered(Ids.newId(), fresh.id(), fresh.language(), now));
                return new LoginResult(true, view(fresh));
            }
            existing = members.lockByPayer(payerId);
        }
        Member before = existing.orElseThrow();
        Member after = before.afterLogin(login.email(), login.name(), login.verified(), provided, now);
        members.update(after);
        if (!before.sameProfileChoices(after)) {
            events.publishEvent(new MemberProfileChanged(Ids.newId(), after.id(), after.language(), after.walletPreference(), now));
        }
        return new LoginResult(false, view(after));
    }

    @Transactional(readOnly = true)
    public Optional<MemberView> find(UUID memberId) {
        return members.find(memberId).map(MemberService::view);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> idForPayer(String paypalPayerId) {
        return paypalPayerId == null ? Optional.empty() : members.idForPayer(paypalPayerId.trim());
    }

    @Transactional(readOnly = true)
    public Optional<String> paypalPayerId(UUID memberId) {
        return members.find(memberId).map(Member::paypalPayerId);
    }

    @Transactional(readOnly = true)
    public Optional<PayoutRecipient> payoutRecipient(UUID memberId) {
        return members.find(memberId).map(m -> new PayoutRecipient(m.paypalPayerId(), m.email()));
    }

    @Transactional
    public MemberView updateProfile(UUID memberId, ProfileUpdate update) {
        Member before = members.lock(memberId).orElseThrow(() -> notFound());
        Member after = before.withProfile(update.language(), update.walletPreference(), update.displayName(), now());
        if (after != before) {
            members.update(after);
            if (!before.sameProfileChoices(after)) {
                events.publishEvent(new MemberProfileChanged(Ids.newId(), after.id(), after.language(),
                        after.walletPreference(), after.updatedAt()));
            }
        }
        return view(after);
    }

    static DomainException notFound() {
        return new DomainException(IdentityErrors.MEMBER_NOT_FOUND, "No such member");
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    static MemberView view(Member m) {
        return new MemberView(m.id(), m.email(), m.displayName(), m.language(), m.walletPreference(), m.paypalVerified(),
                m.createdAt(), m.lastLoginAt());
    }
}
