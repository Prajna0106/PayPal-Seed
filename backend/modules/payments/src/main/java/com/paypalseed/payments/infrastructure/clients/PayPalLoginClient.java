package com.paypalseed.payments.infrastructure.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.paypalseed.payments.api.PayPalLogin;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Log in with PayPal, server to server. The token response has no ID token, so identity comes from userinfo.
 * The tokens carry the app's payment scopes and a refresh token, so they are dropped as soon as this returns.
 * A code works once and a failed attempt uses it up, so nothing here retries. Logs hold statuses and debug ids
 * only: never codes, tokens or personal details.
 */
@Component
public class PayPalLoginClient implements PayPalLogin {

    private static final Logger log = LoggerFactory.getLogger(PayPalLoginClient.class);

    private final RestClient http;
    private final String clientId;
    private final String clientSecret;

    @Autowired
    public PayPalLoginClient(@Value("${seed.paypal.base-url}") String baseUrl,
                             @Value("${seed.paypal.login-client-id:}") String clientId,
                             @Value("${seed.paypal.login-client-secret:}") String clientSecret) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public PayPalIdentity identify(String code, String codeVerifier, String redirectUri) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal login is not configured");
        }
        return readIdentity(accessToken(code, codeVerifier, redirectUri));
    }

    private String accessToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        JsonNode body;
        try {
            body = http.post().uri("/v1/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw refused("token", e, LOGIN_REJECTED);
        }
        String token = body == null ? "" : body.path("access_token").asText("");
        if (token.isBlank()) {
            log.warn("PayPal token response had no access token");
            throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal sent an unusable answer");
        }
        return token;
    }

    private PayPalIdentity readIdentity(String accessToken) {
        JsonNode user;
        try {
            user = http.get().uri("/v1/identity/openidconnect/userinfo?schema=openid")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw refused("userinfo", e, IDENTITY_REFUSED);
        }
        String subject = user == null ? "" : user.path("sub").asText("");
        if (subject.isBlank()) {
            log.warn("PayPal userinfo had no subject");
            throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal sent an unusable answer");
        }
        return new PayPalIdentity(subject, text(user, "payer_id"), text(user, "email"), text(user, "name"),
                user.path("verified_account").asBoolean(false));
    }

    /** A 4xx means PayPal said no; anything else means PayPal couldn't be reached or broke. */
    private static DomainException refused(String step, RestClientException failure, ErrorCode whenRefused) {
        if (failure instanceof RestClientResponseException response) {
            log.warn("PayPal {} call returned HTTP {} (debug id {})", step, response.getStatusCode().value(),
                    response.getResponseHeaders() == null ? null : response.getResponseHeaders().getFirst("paypal-debug-id"));
            if (response.getStatusCode().is4xxClientError()) {
                return new DomainException(whenRefused, whenRefused.title());
            }
        } else {
            log.warn("PayPal {} call failed: {}", step, failure.getClass().getSimpleName());
        }
        return new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "PayPal is unavailable; try again");
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }
}
