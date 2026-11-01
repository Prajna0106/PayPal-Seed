package com.paypalseed;

import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The one deployable. {@code SEED_ROLE} selects what this process does:
 * {@code api} (REST for apps, desk and partners), {@code ingress} (PayPal webhooks) or
 * {@code worker} (inbox, outbox retries, reminders). Each role is a Spring profile.
 */
@SpringBootApplication
@Modulithic(sharedModules = {"platform", "engines"})
@EnableAsync
@EnableScheduling
public class SeedApplication {

    static final Set<String> ROLES = Set.of("api", "ingress", "worker");

    static {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String role = System.getenv().getOrDefault("SEED_ROLE", "api").toLowerCase(Locale.ROOT);
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("SEED_ROLE must be one of " + ROLES + " but was " + role);
        }
        SpringApplication app = new SpringApplication(SeedApplication.class);
        app.setAdditionalProfiles(role);
        app.run(args);
    }
}
