package com.paypalseed.platform.persistence;

import java.time.Duration;
import org.jooq.ExecuteListenerProvider;
import org.jooq.conf.Settings;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot builds the {@code DSLContext} on the Spring-managed DataSource, so jOOQ statements join
 * the surrounding transaction, savepoints included. This adds the production guard rails.
 */
@Configuration(proxyBeanMethods = false)
public class JooqConfig {

    /** A runaway statement fails after the timeout instead of holding a pooled connection. */
    @Bean
    Settings jooqSettings(@Value("${seed.db.query-timeout-seconds:30}") int queryTimeoutSeconds) {
        // Execute logging would print bound values at DEBUG.
        return new Settings().withQueryTimeout(queryTimeoutSeconds).withExecuteLogging(false);
    }

    @Bean
    ExecuteListenerProvider slowQueryListener(@Value("${seed.db.slow-query-ms:250}") long thresholdMillis) {
        return new DefaultExecuteListenerProvider(new SlowQueryListener(Duration.ofMillis(thresholdMillis)));
    }
}
