package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.security.DevHeaderGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DevHeaderGuardTest {

    @Test
    void refusesDevHeadersOutsideDevAndTest() {
        assertThatThrownBy(() -> new DevHeaderGuard(true, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        MockEnvironment demoOnly = new MockEnvironment();
        demoOnly.setActiveProfiles("demo");
        assertThatThrownBy(() -> new DevHeaderGuard(true, demoOnly)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsDevHeadersInDevAndTest() {
        MockEnvironment demoOnYourMachine = new MockEnvironment();
        demoOnYourMachine.setActiveProfiles("demo", "dev");
        assertThatCode(() -> new DevHeaderGuard(true, demoOnYourMachine)).doesNotThrowAnyException();
    }

    @Test
    void offIsAlwaysFine() {
        assertThatCode(() -> new DevHeaderGuard(false, new MockEnvironment())).doesNotThrowAnyException();
    }
}
