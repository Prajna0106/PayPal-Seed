package com.paypalseed.app.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paypalseed.app.home.HomeView.CreditRecordSection;
import com.paypalseed.app.home.HomeView.ErrorCode;
import com.paypalseed.app.home.HomeView.HardshipSection;
import com.paypalseed.app.home.HomeView.LendingSection;
import com.paypalseed.app.home.HomeView.ProfileSection;
import com.paypalseed.app.home.HomeView.SavingsSection;
import com.paypalseed.app.home.HomeView.Section;
import com.paypalseed.app.home.HomeView.SectionError;
import com.paypalseed.app.home.HomeView.UnderwritingSection;
import com.paypalseed.platform.kernel.CommonErrors;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.testing.FakeBusinessClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Section isolation: whatever one facade does, the other sections still come back and nothing leaks. */
class MemberHomeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** Each section answers with a value, nothing, or a failure; the test sets only what it wants to change. */
    private static final class Fake implements HomeSections {
        Function<UUID, Optional<ProfileSection>> profile =
            id -> Optional.of(new ProfileSection("Ana", "es", "PAYPAL"));
        Function<Context, Optional<SavingsSection>> savings = c -> Optional.of(
            new SavingsSection(4, HomeView.MoneyView.usd(7_500), HomeView.MoneyView.usd(30_000), 0, true, List.of()));
        Function<Context, Optional<LendingSection>> lending = c -> Optional.of(new LendingSection(
            new HomeView.EligibilityCard(true, HomeView.MoneyView.usd(120_000), HomeView.MoneyView.usd(15_000), 3, 18,
                List.of()), null, null));
        Function<Context, Optional<UnderwritingSection>> underwriting =
            c -> Optional.of(new UnderwritingSection(true, null));
        Function<Context, Optional<CreditRecordSection>> creditRecord =
            c -> Optional.of(new CreditRecordSection("NEW", null, null));
        Function<Context, Optional<HardshipSection>> hardship =
            c -> Optional.of(new HardshipSection(false, null, null, null));
        final List<Context> seen = new ArrayList<>();

        @Override
        public Optional<ProfileSection> profile(UUID memberId) {
            return profile.apply(memberId);
        }

        @Override
        public Optional<SavingsSection> savings(Context context) {
            seen.add(context);
            return savings.apply(context);
        }

        @Override
        public Optional<LendingSection> lending(Context context) {
            return lending.apply(context);
        }

        @Override
        public Optional<UnderwritingSection> underwriting(Context context) {
            return underwriting.apply(context);
        }

        @Override
        public Optional<CreditRecordSection> creditRecord(Context context) {
            return creditRecord.apply(context);
        }

        @Override
        public Optional<HardshipSection> hardship(Context context) {
            return hardship.apply(context);
        }
    }

    private static MemberHomeService service(Fake sections) {
        return new MemberHomeService(sections, new FakeBusinessClock(TODAY), null);
    }

    @Test
    void whenEverySectionAnswersThereAreNoErrors() {
        HomeView view = service(new Fake()).home(MEMBER);

        assertThat(view.sectionErrors()).isEmpty();
        assertThat(view.generatedOn()).isEqualTo(TODAY);
        assertThat(view.profile()).isNotNull();
        assertThat(view.savings()).isNotNull();
        assertThat(view.lending()).isNotNull();
        assertThat(view.underwriting()).isNotNull();
        assertThat(view.creditRecord()).isNotNull();
        assertThat(view.hardship()).isNotNull();
    }

    @Test
    void aThrowingFacadeIsASectionErrorNotAFailureAndTheRestIsStillReturned() {
        Fake sections = new Fake();
        sections.lending = c -> {
            throw new IllegalStateException("connection refused: jdbc:postgresql://secret-host/seed password=hunter2");
        };

        HomeView view = service(sections).home(MEMBER);

        assertThat(view.lending()).isNull();
        assertThat(view.sectionErrors()).containsExactly(new SectionError(Section.lending, ErrorCode.UNAVAILABLE));
        assertThat(view.profile()).isNotNull();
        assertThat(view.savings()).isNotNull();
        assertThat(view.creditRecord()).isNotNull();
        assertThat(view.hardship()).isNotNull();
    }

    @Test
    void theErrorOnlyCarriesAStableCodeNeverTheExceptionText() throws Exception {
        Fake sections = new Fake();
        sections.hardship = c -> {
            throw new IllegalStateException("password=hunter2 at com.paypalseed.SomeInternalClass");
        };

        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(service(sections).home(MEMBER));

        assertThat(json).contains("\"sectionErrors\"").contains("\"section\":\"hardship\"").contains("\"code\":\"unavailable\"");
        assertThat(json).doesNotContain("hunter2").doesNotContain("SomeInternalClass").doesNotContain("password");
    }

    @Test
    void aModuleSayingNotFoundOrReturningNothingIsNoDataAndOtherRefusalsAreUnavailable() {
        Fake sections = new Fake();
        sections.profile = id -> Optional.empty();
        sections.savings = c -> {
            throw new DomainException(CommonErrors.NOT_FOUND, "no such member");
        };
        sections.underwriting = c -> {
            throw new DomainException(CommonErrors.DEPENDENCY_UNAVAILABLE, "down");
        };

        HomeView view = service(sections).home(MEMBER);

        assertThat(view.profile()).isNull();
        assertThat(view.savings()).isNull();
        assertThat(view.underwriting()).isNull();
        assertThat(view.sectionErrors()).containsExactly(
            new SectionError(Section.profile, ErrorCode.NO_DATA),
            new SectionError(Section.savings, ErrorCode.NO_DATA),
            new SectionError(Section.underwriting, ErrorCode.UNAVAILABLE));
    }

    @Test
    void everySectionCanFailAtOnceAndTheCallStillReturnsAHomeView() {
        Fake sections = new Fake();
        RuntimeException boom = new IllegalStateException("boom");
        sections.profile = id -> {
            throw boom;
        };
        sections.savings = c -> {
            throw boom;
        };
        sections.lending = c -> {
            throw boom;
        };
        sections.underwriting = c -> {
            throw boom;
        };
        sections.creditRecord = c -> {
            throw boom;
        };
        sections.hardship = c -> {
            throw boom;
        };

        HomeView view = service(sections).home(MEMBER);

        assertThat(view.sectionErrors()).hasSize(6).extracting(SectionError::code).containsOnly(ErrorCode.UNAVAILABLE);
        assertThat(view.generatedOn()).isEqualTo(TODAY);
    }

    @Test
    void hintsAreWrittenInTheMembersLanguageAndDefaultToEnglishWithoutAProfile() {
        Fake spanish = new Fake();
        service(spanish).home(MEMBER);
        assertThat(spanish.seen).singleElement().satisfies(c -> {
            assertThat(c.language()).isEqualTo("es");
            assertThat(c.memberId()).isEqualTo(MEMBER);
            assertThat(c.today()).isEqualTo(TODAY);
        });

        Fake none = new Fake();
        none.profile = id -> Optional.empty();
        service(none).home(MEMBER);
        assertThat(none.seen).singleElement().satisfies(c -> assertThat(c.language()).isEqualTo("en"));
    }

    @Test
    void theSectionsAskOnlyForTheMemberTheCallerNamed() {
        Fake sections = new Fake();
        UUID other = UUID.randomUUID();

        service(sections).home(other);

        assertThat(sections.seen).extracting(HomeSections.Context::memberId).containsOnly(other);
    }
}
