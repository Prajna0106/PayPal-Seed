package com.paypalseed.platform.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Actor token settings, validated when the application starts. Outside the dev, test and demo
 * profiles the application refuses to start without a signing secret or with dev header auth on,
 * because either would let a caller choose who they are.
 *
 * <ul>
 *   <li>{@code seed.security.actor-token.secrets}: list of {@code kid:secret} (env
 *       {@code SEED_ACTOR_TOKEN_SECRETS}, comma separated)</li>
 *   <li>{@code seed.security.actor-token.clock-skew-seconds}: 0 to 30, default 30</li>
 * </ul>
 */
public record ActorTokenSettings(ActorTokenKeys keys, Duration clockSkew) {

    static final String SECRETS_PROPERTY = "seed.security.actor-token.secrets";
    static final String SECRETS_ENV = "SEED_ACTOR_TOKEN_SECRETS";
    static final String SKEW_PROPERTY = "seed.security.actor-token.clock-skew-seconds";
    static final String DEV_HEADERS_PROPERTY = "seed.security.dev-header-auth";
    static final Duration MAX_SKEW = Duration.ofSeconds(30);
    private static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "test", "demo");

    public static ActorTokenSettings from(Environment env) {
        List<String> entries = Binder.get(env).bind(SECRETS_PROPERTY, Bindable.listOf(String.class)).orElse(List.of());
        if (entries.isEmpty()) {
            String fromEnv = env.getProperty(SECRETS_ENV);
            if (fromEnv != null) {
                entries = Arrays.asList(fromEnv.split(","));
            }
        }
        ActorTokenKeys keys = ActorTokenKeys.parse(entries);

        long skewSeconds = env.getProperty(SKEW_PROPERTY, Long.class, MAX_SKEW.toSeconds());
        if (skewSeconds < 0 || skewSeconds > MAX_SKEW.toSeconds()) {
            throw new IllegalStateException(SKEW_PROPERTY + " must be between 0 and " + MAX_SKEW.toSeconds());
        }

        boolean devHeaders = env.getProperty(DEV_HEADERS_PROPERTY, Boolean.class, false);
        boolean production = Arrays.stream(env.getActiveProfiles())
                .map(p -> p.toLowerCase(Locale.ROOT))
                .noneMatch(NON_PRODUCTION_PROFILES::contains);
        if (production) {
            if (devHeaders) {
                throw new IllegalStateException(DEV_HEADERS_PROPERTY
                        + " must be false outside the dev, test and demo profiles");
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException(SECRETS_PROPERTY + " (" + SECRETS_ENV
                        + ") must name at least one kid:secret outside the dev, test and demo profiles");
            }
        }
        return new ActorTokenSettings(keys, Duration.ofSeconds(skewSeconds));
    }
}
