package com.paypalseed.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the three public routes per client address (ADR 0030). It answers 429 {@code rate-limited} as an RFC 9457
 * problem with {@code Retry-After}. It runs before the dispatcher, so it writes the problem itself; the shape matches
 * {@code ApiErrorHandler}.
 */
public final class PublicRateLimitFilter extends OncePerRequestFilter {

    static final String PROBLEM_TYPE = "https://problems.paypal-seed.dev/rate-limited";
    private static final String VERIFY_PREFIX = "/api/v1/records/verify/";

    private final FixedWindowRateLimiter limiter;
    private final int trustedProxyHops;

    public PublicRateLimitFilter(PublicRateLimitSettings settings, Clock clock) {
        this.limiter = new FixedWindowRateLimiter(clock, settings.requestsPerMinute(), settings.maxTrackedClients());
        this.trustedProxyHops = settings.trustedProxyHops();
    }

    static boolean isPublicRoute(String method, String path) {
        return switch (method) {
            case "GET" -> path.equals("/api/v1/reserve/summary") || path.startsWith(VERIFY_PREFIX);
            case "POST" -> path.equals("/api/v1/reserve/donations/intent");
            default -> false;
        };
    }

    /**
     * The address the nearest trusted proxy saw. Entries a client put at the front of {@code X-Forwarded-For} are
     * ignored: with N trusted hops only the Nth entry from the right counts. Too few entries means the request did not
     * come through the proxies we trust, so the socket address is used.
     */
    static String clientAddress(String forwardedFor, String remoteAddr, int trustedProxyHops) {
        if (trustedProxyHops == 0 || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        String[] parts = forwardedFor.split(",");
        if (parts.length < trustedProxyHops) {
            return remoteAddr;
        }
        String candidate = parts[parts.length - trustedProxyHops].trim();
        return candidate.isEmpty() || candidate.length() > 64 ? remoteAddr : candidate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isPublicRoute(request.getMethod(), request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = clientAddress(request.getHeader("X-Forwarded-For"), request.getRemoteAddr(), trustedProxyHops);
        FixedWindowRateLimiter.Decision decision = limiter.tryAcquire(client);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"" + PROBLEM_TYPE + "\",\"title\":\"Too many requests\","
                + "\"status\":429,\"detail\":\"Too many requests from this address. Try again shortly.\","
                + "\"code\":\"rate-limited\"}");
    }
}
