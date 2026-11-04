package com.paypalseed.deskquery.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The desk's SLA settings and the start-up guard of the cursor key (ADR 0027). */
class DeskQuerySettingsTest {

    private static final String KEY = "a-cursor-key-shared-by-every-instance-0123456789";

    private ApplicationContextRunner startedAs(String... profiles) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DeskQuerySettings.class)
                .withInitializer(context -> {
                    context.getEnvironment().setActiveProfiles(profiles);
                    // What Spring Boot sets up for the real application: "PT24H" binds to a Duration.
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
                });
    }

    @Test
    void anApplicationHasTwentyFourHoursAndIsDueSoonFourHoursBeforeThat() {
        startedAs("test").run(context -> {
            DeskQuerySettings settings = context.getBean(DeskQuerySettings.class);
            assertThat(settings.applicationSla()).isEqualTo(Duration.ofHours(24));
            assertThat(settings.dueSoonWindow()).isEqualTo(Duration.ofHours(4));
        });
    }

    @Test
    void bothWindowsAreSettings() {
        startedAs("prod").withPropertyValues("seed.desk-query.cursor-key=" + KEY, "seed.desk-query.application-sla=PT48H",
                "seed.desk-query.sla-due-soon=PT6H").run(context -> {
                    DeskQuerySettings settings = context.getBean(DeskQuerySettings.class);
                    assertThat(settings.applicationSla()).isEqualTo(Duration.ofHours(48));
                    assertThat(settings.dueSoonWindow()).isEqualTo(Duration.ofHours(6));
                });
    }

    @Test
    void productionDoesNotStartWithoutACursorKey() {
        startedAs("prod").run(context -> assertThat(context.getStartupFailure()).rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("seed.desk-query.cursor-key")
                .hasMessageContaining("SEED_DESK_QUERY_CURSOR_KEY")
                .hasMessageContaining("every api instance"));
    }

    @Test
    void aBlankKeyIsAnEmptyKey() {
        startedAs("prod").withPropertyValues("seed.desk-query.cursor-key=   ")
                .run(context -> assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("seed.desk-query.cursor-key"));
    }

    @Test
    void noProfileAtAllIsProduction() {
        startedAs().run(context -> assertThat(context).hasFailed());
    }

    @Test
    void productionStartsWithAKeyAndTheKeyIsNotInAnyMessage() {
        startedAs("prod").withPropertyValues("seed.desk-query.cursor-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DeskQuerySettings.class));
        startedAs("prod").run(context -> assertThat(String.valueOf(context.getStartupFailure())).doesNotContain(KEY));
    }

    @Test
    void devTestAndDemoKeepGeneratingAKeyPerProcess() {
        for (String profile : new String[] {"dev", "test", "demo", "DEMO"}) {
            startedAs(profile).run(context -> assertThat(context).as(profile).hasNotFailed().hasSingleBean(DeskQuerySettings.class));
        }
    }

    @Test
    void oneDevelopmentProfileAmongOthersIsEnough() {
        startedAs("prod", "demo").run(context -> assertThat(context).hasNotFailed());
    }
}
