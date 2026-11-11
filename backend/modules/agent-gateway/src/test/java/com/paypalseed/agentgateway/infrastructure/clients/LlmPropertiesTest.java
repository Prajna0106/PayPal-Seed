package com.paypalseed.agentgateway.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

/**
 * OpenRouter answers a retired model in a fallback chain with a 404 and silently serves the next id, so a
 * stale first entry looks like a working chain while the later models carry every call.
 */
class LlmPropertiesTest {

    /** Ids OpenRouter reports as "no longer available": never the head of the chain again. */
    private static final String RETIRED = "google/gemini-2.0-flash-001";
    private static final String PRIMARY = "google/gemini-2.5-flash-lite";

    @Test
    void defaultChainStartsWithTheLiveGeminiFlashModelAndKeepsTheFallbackOrder() {
        LlmProperties properties = binder(Map.of())
                .bindOrCreate("seed.llm", LlmProperties.class);

        assertThat(properties.models()).containsExactly(PRIMARY, "meta-llama/llama-3.3-70b-instruct",
                "mistralai/mistral-small-24b-instruct-2501");
        assertThat(properties.models()).doesNotContain(RETIRED);
    }

    @Test
    void theChainIsBoundInTheGivenOrder() {
        LlmProperties properties = binder(Map.of("seed.llm.models", "a/primary,b/second,c/third"))
                .bindOrCreate("seed.llm", LlmProperties.class);

        assertThat(properties.models()).containsExactly("a/primary", "b/second", "c/third");
    }

    /** Boot's own conversion service splits a comma-separated default into a list, as it does at startup. */
    private static Binder binder(Map<String, Object> source) {
        return new Binder(List.of(new MapConfigurationPropertySource(source)), null, ApplicationConversionService.getSharedInstance());
    }
}
