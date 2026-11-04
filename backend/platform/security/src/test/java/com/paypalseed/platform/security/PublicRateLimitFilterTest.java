package com.paypalseed.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitFilterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-10T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private PublicRateLimitFilter filter(int limit, int hops, int maxClients) {
        return new PublicRateLimitFilter(new PublicRateLimitSettings(true, limit, hops, maxClients), clock);
    }

    private MockHttpServletResponse call(PublicRateLimitFilter f, String remote, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reserve/summary");
        request.setRemoteAddr(remote);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        f.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void refusesWithAProblemAndRetryAfterOnceTheLimitIsHit() throws Exception {
        PublicRateLimitFilter f = filter(2, 0, 100);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = call(f, "10.0.0.1", null);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        assertThat(limited.getContentType()).startsWith("application/problem+json");
        assertThat(limited.getContentAsString()).contains("\"code\":\"rate-limited\"", PublicRateLimitFilter.PROBLEM_TYPE);
    }

    @Test
    void retryAfterShrinksAndTheWindowResets() throws Exception {
        PublicRateLimitFilter f = filter(1, 0, 100);
        call(f, "10.0.0.1", null);
        clock.advance(Duration.ofSeconds(45));
        assertThat(call(f, "10.0.0.1", null).getHeader("Retry-After")).isEqualTo("15");

        clock.advance(Duration.ofSeconds(15));
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);
    }

    @Test
    void differentAddressesAreCountedApart() throws Exception {
        PublicRateLimitFilter f = filter(1, 0, 100);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(429);
        assertThat(call(f, "10.0.0.2", null).getStatus()).isEqualTo(200);
    }

    @Test
    void aSpoofedForwardedForPrefixDoesNotBuyAFreshAllowance() throws Exception {
        PublicRateLimitFilter f = filter(1, 1, 100);
        assertThat(call(f, "10.0.0.9", "1.1.1.1, 203.0.113.7").getStatus()).isEqualTo(200);
        assertThat(call(f, "10.0.0.9", "2.2.2.2, 203.0.113.7").getStatus()).isEqualTo(429);
        assertThat(call(f, "10.0.0.9", "203.0.113.8").getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedForIsIgnoredWithNoTrustedProxyAndShortChainsFallBackToTheSocket() {
        assertThat(PublicRateLimitFilter.clientAddress("1.1.1.1", "10.0.0.9", 0)).isEqualTo("10.0.0.9");
        assertThat(PublicRateLimitFilter.clientAddress(null, "10.0.0.9", 1)).isEqualTo("10.0.0.9");
        assertThat(PublicRateLimitFilter.clientAddress("1.1.1.1", "10.0.0.9", 2)).isEqualTo("10.0.0.9");
        assertThat(PublicRateLimitFilter.clientAddress("1.1.1.1, 2.2.2.2, 3.3.3.3", "10.0.0.9", 2)).isEqualTo("2.2.2.2");
    }

    @Test
    void theMapRefusesNewAddressesAtItsBoundAndRecoversWhenWindowsExpire() throws Exception {
        PublicRateLimitFilter f = filter(5, 0, 2);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);
        assertThat(call(f, "10.0.0.2", null).getStatus()).isEqualTo(200);
        assertThat(call(f, "10.0.0.3", null).getStatus()).isEqualTo(429);
        assertThat(call(f, "10.0.0.1", null).getStatus()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(1));
        assertThat(call(f, "10.0.0.3", null).getStatus()).isEqualTo(200);
    }

    @Test
    void limiterNeverTracksMoreKeysThanItsBound() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, 5, 3);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("k" + i);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(3);
    }

    @Test
    void otherRoutesAndMethodsAreNotLimited() {
        assertThat(PublicRateLimitFilter.isPublicRoute("GET", "/api/v1/records/verify/v1.k1.a.b")).isTrue();
        assertThat(PublicRateLimitFilter.isPublicRoute("GET", "/api/v1/reserve/summary")).isTrue();
        assertThat(PublicRateLimitFilter.isPublicRoute("POST", "/api/v1/reserve/donations/intent")).isTrue();
        assertThat(PublicRateLimitFilter.isPublicRoute("POST", "/api/v1/reserve/summary")).isFalse();
        assertThat(PublicRateLimitFilter.isPublicRoute("GET", "/api/v1/home")).isFalse();
    }
}
