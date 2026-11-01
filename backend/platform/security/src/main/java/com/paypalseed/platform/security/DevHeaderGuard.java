package com.paypalseed.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Dev headers let a caller be anyone, so the application refuses to start with them outside dev and test. */
@Component
public class DevHeaderGuard {

    public DevHeaderGuard(@Value("${seed.security.dev-header-auth:false}") boolean devHeaders, Environment environment) {
        if (devHeaders && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException(
                    "seed.security.dev-header-auth is on outside the dev and test profiles: anyone could act as anyone");
        }
    }
}
