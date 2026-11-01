package com.paypalseed.agentgateway.infrastructure.clients;

import com.paypalseed.agentgateway.api.KillSwitch;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code seed.llm.*}. The model chain is configuration, not code: a model enters it only after it passes
 * the eval gates (04 §8, §14). Without an API key the gateway runs with templates only.
 */
@ConfigurationProperties("seed.llm")
public record LlmProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://openrouter.ai/api/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue({"openai/gpt-4o-mini", "anthropic/claude-3.5-haiku"}) List<String> models,
        @DefaultValue("20000000") long dailyBudgetMicroUsd,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("20s") Duration callTimeout,
        @DefaultValue("https://github.com/airajena/PayPal-Seed") String appUrl,
        @DefaultValue("LIVE") KillSwitch.Mode defaultMode) {

    public LlmProperties {
        models = List.copyOf(models);
        if (models.isEmpty()) {
            throw new IllegalArgumentException("seed.llm.models needs at least one model");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("seed.llm.max-concurrent must be at least 1");
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "LlmProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "***" : "<none>")
                + ", models=" + models + "]";
    }
}
