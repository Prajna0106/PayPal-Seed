package com.paypalseed.identity.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.identity.application.IdentityErrors;
import com.paypalseed.identity.application.ports.PayPalLogin.PayPalIdentity;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The PayPal adapter against a local stub, using the field names a real sandbox login returned. */
class PayPalLoginClientTest {

    private static final String TOKEN_BODY = """
            {"access_token":"pp-access","refresh_token":"pp-refresh","token_type":"Bearer","expires_in":28800}""";
    private static final String USER_BODY = """
            {"sub":"subject-1","user_id":"subject-1","payer_id":"PAYER1","name":"Ana Diaz","email":"ana@example.test",
             "verified":true,"email_verified":true,"verified_account":true}""";

    private final AtomicReference<String> tokenRequest = new AtomicReference<>();
    private final AtomicReference<String> tokenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> userAuthorization = new AtomicReference<>();
    private final AtomicReference<String> userQuery = new AtomicReference<>();
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private volatile int tokenStatus = 200;
    private volatile String tokenBody = TOKEN_BODY;
    private volatile int userStatus = 200;
    private volatile String userBody = USER_BODY;
    private HttpServer server;
    private PayPalLoginClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/oauth2/token", exchange -> {
            tokenCalls.incrementAndGet();
            tokenRequest.set(URLDecoder.decode(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, tokenStatus, tokenBody);
        });
        server.createContext("/v1/identity/openidconnect/userinfo", exchange -> {
            userAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            userQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, userStatus, userBody);
        });
        server.start();
        client = new PayPalLoginClient("http://127.0.0.1:" + server.getAddress().getPort(), "the-client", "the-secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void swapsTheCodeThenReadsWhoLoggedIn() {
        PayPalIdentity identity = client.identify("the-code", "the-verifier", "http://127.0.0.1:3000/cb");

        assertThat(identity).isEqualTo(new PayPalIdentity("subject-1", "PAYER1", "ana@example.test", "Ana Diaz", true));
        assertThat(tokenRequest.get()).contains("grant_type=authorization_code", "code=the-code",
                "code_verifier=the-verifier", "redirect_uri=http://127.0.0.1:3000/cb");
        assertThat(tokenAuthorization.get()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("the-client:the-secret".getBytes(StandardCharsets.UTF_8)));
        assertThat(userAuthorization.get()).isEqualTo("Bearer pp-access");
        assertThat(userQuery.get()).isEqualTo("schema=openid");
    }

    @Test
    void aRefusedCodeIsNotRetriedBecauseTheFirstAttemptUsesItUp() {
        tokenStatus = 401;
        tokenBody = "{\"error\":\"invalid_authz_code\"}";

        assertRefusedWith(IdentityErrors.PAYPAL_LOGIN_REJECTED);
        assertThat(tokenCalls).hasValue(1);
    }

    @Test
    void userinfoRefusalIsItsOwnError() {
        userStatus = 400;
        userBody = "{\"error\":\"invalid_request\",\"error_description\":\"Unable to fetch details to serve the request\"}";

        assertRefusedWith(IdentityErrors.PAYPAL_IDENTITY_REFUSED);
    }

    @Test
    void aPayPalOutageMeansTryAgainNotBadLogin() {
        tokenStatus = 503;
        tokenBody = "{}";

        assertRefusedWith(CommonErrors.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void aMissingSubjectIsNeverAccepted() {
        userBody = "{\"name\":\"No Subject\"}";

        assertRefusedWith(CommonErrors.DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void refusesToRunWithoutCredentials() {
        PayPalLoginClient unconfigured = new PayPalLoginClient("http://127.0.0.1:1", "", "");

        assertThatThrownBy(() -> unconfigured.identify("c", "v", "r"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode()).isEqualTo(CommonErrors.DEPENDENCY_UNAVAILABLE));
    }

    private void assertRefusedWith(ErrorCode code) {
        assertThatThrownBy(() -> client.identify("the-code", "the-verifier", "http://127.0.0.1:3000/cb"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).errorCode()).isEqualTo(code));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
