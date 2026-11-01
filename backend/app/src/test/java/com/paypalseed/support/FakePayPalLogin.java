package com.paypalseed.support;

import com.paypalseed.identity.application.IdentityErrors;
import com.paypalseed.identity.application.ports.PayPalLogin;
import com.paypalseed.platform.kernel.DomainException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Knows which one-time codes are good and who each one logs in. Like the real thing, a code works only once. */
public class FakePayPalLogin implements PayPalLogin {

    private final Map<String, PayPalIdentity> codes = new ConcurrentHashMap<>();

    public void willLogIn(String code, PayPalIdentity identity) {
        codes.put(code, identity);
    }

    @Override
    public PayPalIdentity identify(String code, String codeVerifier, String redirectUri) {
        PayPalIdentity identity = codes.remove(code);
        if (identity == null) {
            throw new DomainException(IdentityErrors.PAYPAL_LOGIN_REJECTED, "unknown code");
        }
        return identity;
    }
}
