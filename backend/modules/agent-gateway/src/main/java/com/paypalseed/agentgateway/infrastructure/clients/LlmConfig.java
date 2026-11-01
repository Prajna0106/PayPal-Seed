package com.paypalseed.agentgateway.infrastructure.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.agentgateway.api.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    LlmClient llmClient(LlmProperties properties, ObjectMapper json) {
        if (!properties.enabled() || !properties.hasApiKey()) {
            log.info("No model API key configured: agents run on deterministic templates only");
            return new DisabledLlmClient();
        }
        log.info("Model gateway uses OpenRouter with chain {}", properties.models());
        return new OpenRouterLlmClient(OpenRouterLlmClient.defaultHttpClient(), json, properties.baseUrl(),
                properties.apiKey(), properties.appUrl());
    }
}
