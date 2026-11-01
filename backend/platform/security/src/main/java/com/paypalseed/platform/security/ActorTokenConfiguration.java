package com.paypalseed.platform.security;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Reads and validates the actor token settings once, so a bad production setup stops the boot. */
@Configuration(proxyBeanMethods = false)
public class ActorTokenConfiguration {

    @Bean
    ActorTokenSettings actorTokenSettings(Environment environment) {
        return ActorTokenSettings.from(environment);
    }

    @Bean
    ActorTokenVerifier actorTokenVerifier(ActorTokenSettings settings) {
        return new ActorTokenVerifier(settings.keys(), settings.clockSkew(), Clock.systemUTC());
    }
}
