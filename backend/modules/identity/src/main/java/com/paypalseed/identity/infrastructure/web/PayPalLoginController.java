package com.paypalseed.identity.infrastructure.web;

import com.paypalseed.identity.application.usecases.PayPalLoginService;
import com.paypalseed.identity.application.usecases.PayPalLoginService.LoggedIn;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Called by the member web app's server after PayPal redirects back. The browser never reaches this route. */
@RestController
class PayPalLoginController {

    record ExchangeRequest(@NotBlank @Size(max = 4096) String code,
                           @NotBlank @Size(max = 256) String codeVerifier,
                           @NotBlank @Size(max = 2048) String redirectUri) {
    }

    private final PayPalLoginService logins;

    PayPalLoginController(PayPalLoginService logins) {
        this.logins = logins;
    }

    @PostMapping("/internal/auth/paypal/exchange")
    LoggedIn exchange(Actor actor, @Valid @RequestBody ExchangeRequest request) {
        actor.requireAnyRole(Role.SERVICE);
        return logins.logIn(request.code(), request.codeVerifier(), request.redirectUri());
    }
}
