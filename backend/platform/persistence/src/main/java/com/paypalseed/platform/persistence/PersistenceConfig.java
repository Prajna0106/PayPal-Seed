package com.paypalseed.platform.persistence;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.SystemBusinessClock;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    @Bean(initMethod = "migrate")
    ModuleMigrations moduleMigrations(DataSource dataSource) {
        return new ModuleMigrations(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "seed.clock.mode", havingValue = "database")
    DatabaseBusinessClock databaseBusinessClock(JdbcClient jdbc, ModuleMigrations migrations) {
        return new DatabaseBusinessClock(jdbc, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = "seed.clock.mode", havingValue = "system", matchIfMissing = true)
    BusinessClock systemBusinessClock() {
        return new SystemBusinessClock();
    }
}
