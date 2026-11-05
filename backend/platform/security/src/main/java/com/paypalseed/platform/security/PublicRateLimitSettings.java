package com.paypalseed.platform.security;

import org.springframework.core.env.Environment;

/**
 * Settings of the limit on the public routes (ADR 0030).
 *
 * <ul>
 *   <li>{@code seed.security.public-rate-limit.enabled}: default true</li>
 *   <li>{@code seed.security.public-rate-limit.requests-per-minute}: per client address, 1 or more, default 60</li>
 *   <li>{@code seed.security.public-rate-limit.trusted-proxy-hops}: proxies in front of the app that append to
 *       {@code X-Forwarded-For}, 0 to 5, default 1 (the platform load balancer)</li>
 *   <li>{@code seed.security.public-rate-limit.max-tracked-clients}: addresses counted at once, default 10000; at the
 *       bound new addresses are refused rather than the map growing</li>
 * </ul>
 */
public record PublicRateLimitSettings(boolean enabled, int requestsPerMinute, int trustedProxyHops, int maxTrackedClients) {

    static final String PREFIX = "seed.security.public-rate-limit.";

    public PublicRateLimitSettings {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException(PREFIX + "requests-per-minute must be at least 1");
        }
        if (trustedProxyHops < 0 || trustedProxyHops > 5) {
            throw new IllegalArgumentException(PREFIX + "trusted-proxy-hops must be between 0 and 5");
        }
        if (maxTrackedClients < 1) {
            throw new IllegalArgumentException(PREFIX + "max-tracked-clients must be at least 1");
        }
    }

    public static PublicRateLimitSettings from(Environment env) {
        return new PublicRateLimitSettings(
                env.getProperty(PREFIX + "enabled", Boolean.class, true),
                env.getProperty(PREFIX + "requests-per-minute", Integer.class, 60),
                env.getProperty(PREFIX + "trusted-proxy-hops", Integer.class, 1),
                env.getProperty(PREFIX + "max-tracked-clients", Integer.class, 10_000));
    }
}
