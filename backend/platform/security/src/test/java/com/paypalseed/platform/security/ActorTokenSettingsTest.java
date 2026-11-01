package com.paypalseed.platform.security;

import static com.paypalseed.platform.security.TokenForge.SECRET_1;
import static com.paypalseed.platform.security.TokenForge.SECRET_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ActorTokenSettingsTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void productionRefusesToStartWithoutASecret() {
        assertThatThrownBy(() -> ActorTokenSettings.from(env("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.security.actor-token.secrets");
        assertThatThrownBy(() -> ActorTokenSettings.from(env()))
                .as("no profile at all counts as production")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRefusesDevHeaderAuth() {
        MockEnvironment env = env("prod").withProperty("seed.security.actor-token.secrets[0]", "k1:" + SECRET_1)
                .withProperty("seed.security.dev-header-auth", "true");
        assertThatThrownBy(() -> ActorTokenSettings.from(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-header-auth");
    }

    @Test
    void productionStartsWithASecretAndNoDevHeaders() {
        MockEnvironment env = env("prod").withProperty("seed.security.actor-token.secrets[0]", "k1:" + SECRET_1)
                .withProperty("seed.security.dev-header-auth", "false");
        ActorTokenSettings settings = ActorTokenSettings.from(env);
        assertThat(settings.keys().size()).isEqualTo(1);
        assertThat(settings.clockSkew()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void devTestAndDemoMayRunWithoutASecretAndWithDevHeaders() {
        for (String profile : new String[] {"dev", "test", "demo", "DEV"}) {
            MockEnvironment env = env(profile).withProperty("seed.security.dev-header-auth", "true");
            assertThat(ActorTokenSettings.from(env).keys().isEmpty()).isTrue();
        }
    }

    @Test
    void secretsBindFromACommaSeparatedValueAndFromTheDocumentedEnvironmentVariable() {
        ActorTokenSettings fromProperty = ActorTokenSettings.from(env("prod")
                .withProperty("seed.security.actor-token.secrets", "k2:" + SECRET_2 + ", k1:" + SECRET_1));
        assertThat(fromProperty.keys().size()).isEqualTo(2);
        assertThat(fromProperty.keys().signingKid()).isEqualTo("k2");

        ActorTokenSettings fromEnv = ActorTokenSettings.from(env("prod")
                .withProperty("SEED_ACTOR_TOKEN_SECRETS", "k1:" + SECRET_1 + ",k2:" + SECRET_2));
        assertThat(fromEnv.keys().signingKid()).isEqualTo("k1");
        assertThat(fromEnv.keys().secret("k2")).isEqualTo(SECRET_2.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidEntriesAreRefusedWithoutEchoingTheSecret() {
        String shortSecret = "x".repeat(31);
        assertThatThrownBy(() -> ActorTokenKeys.parse(List.of("k1:" + shortSecret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32")
                .hasMessageNotContaining(shortSecret);
        assertThatThrownBy(() -> ActorTokenKeys.parse(List.of(SECRET_1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET_1);
        assertThatThrownBy(() -> ActorTokenKeys.parse(List.of("bad kid:" + SECRET_1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET_1);
        assertThatThrownBy(() -> ActorTokenKeys.parse(List.of("k1:" + SECRET_1, "k1:" + SECRET_2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET_2);
        assertThat(ActorTokenKeys.parse(List.of("k1:" + "a".repeat(32))).size()).isEqualTo(1);
    }

    @Test
    void clockSkewIsCappedAtThirtySeconds() {
        MockEnvironment ok = env("dev").withProperty("seed.security.actor-token.clock-skew-seconds", "5");
        assertThat(ActorTokenSettings.from(ok).clockSkew()).isEqualTo(Duration.ofSeconds(5));
        for (String bad : new String[] {"31", "-1"}) {
            MockEnvironment env = env("dev").withProperty("seed.security.actor-token.clock-skew-seconds", bad);
            assertThatThrownBy(() -> ActorTokenSettings.from(env)).isInstanceOf(IllegalStateException.class);
        }
    }
}
