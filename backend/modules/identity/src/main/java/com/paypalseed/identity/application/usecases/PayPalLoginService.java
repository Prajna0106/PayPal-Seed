package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.application.IdentityErrors;
import com.paypalseed.identity.application.ports.PayPalLogin;
import com.paypalseed.identity.application.ports.PayPalLogin.PayPalIdentity;
import com.paypalseed.identity.application.usecases.SessionService.IssuedSession;
import com.paypalseed.identity.infrastructure.persistence.IdentityRepository;
import com.paypalseed.identity.infrastructure.persistence.IdentityRepository.StoredMember;
import com.paypalseed.platform.kernel.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** A first login creates the member; a later one finds them by PayPal's permanent subject and refreshes their details. */
@Service
public class PayPalLoginService {

    public record LoggedIn(UUID memberId, String token, Instant expiresAt) {
    }

    private final PayPalLogin paypal;
    private final IdentityRepository repository;
    private final SessionService sessions;

    public PayPalLoginService(PayPalLogin paypal, IdentityRepository repository, SessionService sessions) {
        this.paypal = paypal;
        this.repository = repository;
        this.sessions = sessions;
    }

    public LoggedIn logIn(String code, String codeVerifier, String redirectUri) {
        PayPalIdentity identity = paypal.identify(code, codeVerifier, redirectUri);
        StoredMember member = repository.upsertMember(UUID.randomUUID(), identity.subject(), identity.payerId(),
                identity.email(), identity.name(), identity.verifiedAccount());
        if (!member.active()) {
            throw new DomainException(IdentityErrors.ACCOUNT_SUSPENDED, IdentityErrors.ACCOUNT_SUSPENDED.title());
        }
        IssuedSession session = sessions.issueForMember(member.id());
        return new LoggedIn(member.id(), session.token(), session.expiresAt());
    }
}
