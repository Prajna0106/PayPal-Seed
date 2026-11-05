package com.paypalseed.platform.security;

import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class PublicRateLimitConfiguration {

    @Bean
    FilterRegistrationBean<PublicRateLimitFilter> publicRateLimitFilter(Environment environment) {
        PublicRateLimitSettings settings = PublicRateLimitSettings.from(environment);
        FilterRegistrationBean<PublicRateLimitFilter> registration =
                new FilterRegistrationBean<>(new PublicRateLimitFilter(settings, Clock.systemUTC()));
        registration.setEnabled(settings.enabled());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
