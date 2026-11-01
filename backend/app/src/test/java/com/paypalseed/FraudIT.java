package com.paypalseed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paypalseed.fraud.api.FraudFacade;
import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagStatus;
import com.paypalseed.fraud.api.dto.FraudDtos.FlagView;
import com.paypalseed.fraud.api.dto.FraudDtos.Observation;
import com.paypalseed.fraud.api.events.FraudEvents.FraudFlagRaised;
import com.paypalseed.fraud.api.events.FraudEvents.FraudFlagReviewed;
import com.paypalseed.governance.api.GovernanceFacade;
import com.paypalseed.identity.api.IdentityFacade;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.dto.IdentityDtos.ProfileUpdate;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.api.events.IdentityEvents.MemberRegistered;
import com.paypalseed.lending.api.dto.LendingDtos.LoanApplicationView;
import com.paypalseed.lending.api.dto.LendingDtos.LoanSummary;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService;
import com.paypalseed.lending.application.usecases.MicroloanApplicationService.SubmitApplicationCommand;
import com.paypalseed.lending.domain.model.LoanApplication.WalletType;
import com.paypalseed.payments.api.events.PaymentEvents.PaymentCaptured;
import com.paypalseed.payments.api.events.PaymentEvents.Source;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.kernel.Money;
import com.paypalseed.savings.api.events.DepositReceivedEvent;
import com.paypalseed.savings.api.events.GoalCreatedEvent;
import com.paypalseed.savings.api.events.WithdrawalProcessedEvent;
import com.paypalseed.savings.application.usecases.CreateGoalUseCase;
import com.paypalseed.savings.application.usecases.RecordDepositUseCase;
import com.paypalseed.support.IntegrationTest;
import com.paypalseed.underwriting.application.usecases.FairLoanCheckService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fraud signals and flags: every rule fires on a crafted scenario and stays quiet on a normal one, a flag is advice that
 * blocks nobody, and only an officer can review it, with an audit entry and an evidence pack.
 */
@RecordApplicationEvents
class FraudIT extends IntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired
    FraudFacade fraud;
    @Autowired
    GovernanceFacade governance;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ObjectMapper json;
    @Autowired
    ApplicationEvents published;
    @Autowired
    IdentityFacade identity;
    @Autowired
    CreateGoalUseCase createGoal;
    @Autowired
    RecordDepositUseCase deposits;
    @Autowired
    FairLoanCheckService fairCheck;
    @Autowired
    MicroloanApplicationService microloans;

    // ---- the rules, one scenario each ------------------------------------------------------------------------

    @Test
    void goalsAndApplicationsInABurstFireTheirRulesAndTogetherRaiseAFlag() {
        UUID member = member();
        observe(member, FactKind.ACCOUNT_CREATED, hoursAgo(24 * 30), "acct", null, null);
        for (int i = 0; i < 3; i++) {
            observe(member, FactKind.GOAL_CREATED, hoursAgo(5 - i), "g" + i, null, null);
        }
        assertThat(signals(member)).as("three goals in a day is normal").isEmpty();

        observe(member, FactKind.GOAL_CREATED, hoursAgo(1), "g3", null, null);
        assertThat(signals(member)).containsExactly("GOAL_VELOCITY");
        assertThat(fraud.flagOf(member)).as("20 points is below the 40 needed for a flag").isEmpty();

        observe(member, FactKind.APPLICATION_SUBMITTED, hoursAgo(3), "a1", null, null);
        observe(member, FactKind.APPLICATION_SUBMITTED, hoursAgo(2), "a2", null, null);
        assertThat(signals(member)).containsExactlyInAnyOrder("GOAL_VELOCITY", "APPLICATION_VELOCITY");
        FlagView flag = fraud.flagOf(member).orElseThrow();
        assertThat(flag.status()).isEqualTo(FlagStatus.OPEN);
        assertThat(flag.score()).isEqualTo(50);
        assertThat(flag.signalCodes()).containsExactlyInAnyOrder("GOAL_VELOCITY", "APPLICATION_VELOCITY");
    }

    @Test
    void aPayerSharedByMembersFlagsEveryoneInvolvedAndStoresOnlyAHash() {
        String payer = "PAYER" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID first = member();
        UUID second = member();
        UUID stranger = member();
        observe(first, FactKind.PAYER_SEEN, hoursAgo(10), "p1", null, payer);
        assertThat(signals(first)).as("a payer seen once pays for one member").isEmpty();
        observe(stranger, FactKind.PAYER_SEEN, hoursAgo(9), "p-other", null, "OTHER" + payer);
        observe(second, FactKind.PAYER_SEEN, hoursAgo(8), "p2", null, "  " + payer.toLowerCase() + " ");

        assertThat(signals(second)).containsExactly("SHARED_PAYER");
        assertThat(signals(first)).as("the first member is re-evaluated when the second appears").containsExactly("SHARED_PAYER");
        assertThat(signals(stranger)).isEmpty();
        assertThat(fraud.flagOf(first)).get().extracting(FlagView::status).isEqualTo(FlagStatus.OPEN);
        assertThat(fraud.flagOf(second)).isPresent();
        assertThat(fraud.flagOf(stranger)).isEmpty();

        // The identifier is never stored, not even as a plain hash an attacker could recompute.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fraud.fact WHERE ref_hash ILIKE '%' || :p || '%'").param("p", payer)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fraud.fact WHERE ref_hash = :h").param("h", sha256(payer.toLowerCase()))
                .query(Integer.class).single()).isZero();
        List<String> hashes = jdbc.sql("SELECT DISTINCT ref_hash FROM fraud.fact WHERE member_id IN (:a, :b)")
                .param("a", first).param("b", second).query(String.class).list();
        assertThat(hashes).as("same payer, whatever the spacing or case").hasSize(1);
        assertThat(hashes.get(0)).hasSize(64);
    }

    @Test
    void aPayerEventFromPaymentsBecomesAFactForTheLoansMember() {
        String payer = "EVT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID one = member();
        UUID two = member();
        clock.set(DAY);
        LoanSummary loanOne = disburse(one, 300, 6, 15, DAY);
        LoanSummary loanTwo = disburse(two, 300, 6, 15, DAY);
        settle();
        for (LoanSummary loan : List.of(loanOne, loanTwo)) {
            tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(Ids.newId(), loan.loanId(),
                    "TXN" + Ids.newId().toString().substring(0, 12), Source.CHECKOUT, 5_000, 0, clock.now(), payer, "test")));
            settle();
        }
        assertThat(signals(one)).contains("SHARED_PAYER");
        assertThat(signals(two)).contains("SHARED_PAYER");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fraud.fact WHERE member_id = :m AND kind = 'PAYER_SEEN'").param("m", two)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void moneyInAndStraightBackOutIsChurnAndARepeatCountsMore() {
        UUID member = member();
        observe(member, FactKind.DEPOSIT, hoursAgo(100), "d1", 10_000L, null);
        observe(member, FactKind.WITHDRAWAL, hoursAgo(98), "w1", 10_000L, null);
        assertThat(signals(member)).containsExactly("DEPOSIT_CHURN");
        assertThat(weights(member)).containsExactly(15);

        observe(member, FactKind.DEPOSIT, hoursAgo(40), "d2", 5_000L, null);
        observe(member, FactKind.WITHDRAWAL, hoursAgo(39), "w2", 5_000L, null);
        assertThat(weights(member)).containsExactlyInAnyOrder(15, 35);
        assertThat(fraud.flagOf(member)).as("35 points is still advice-free").isEmpty();

        for (int i = 0; i < 3; i++) {
            observe(member, FactKind.PAYMENT_FAILED, hoursAgo(30 - i), "f" + i, 5_000L, null);
        }
        assertThat(signals(member)).contains("REPEATED_FAILED_PAYMENTS");
        FlagView flag = fraud.flagOf(member).orElseThrow();
        assertThat(flag.score()).as("heaviest churn signal (35) plus failed payments (10)").isEqualTo(45);
    }

    @Test
    void savingForMonthsAndThenWithdrawingIsNotChurn() {
        UUID member = member();
        for (int i = 0; i < 4; i++) {
            observe(member, FactKind.DEPOSIT, hoursAgo(24L * 80 - 24L * 20 * i), "nd" + i, 5_000L, null);
        }
        observe(member, FactKind.WITHDRAWAL, hoursAgo(2), "nw", 20_000L, null);
        assertThat(signals(member)).isEmpty();
        assertThat(fraud.flagOf(member)).isEmpty();
    }

    @Test
    void depositAndWithdrawalEventsFromSavingsFeedTheSameRule() {
        UUID member = member();
        UUID goal = Ids.newId();
        clock.set(DAY);
        tx.executeWithoutResult(s -> publisher.publishEvent(DepositReceivedEvent.now(member.toString(), goal, Money.ofCents(12_000),
                "CAP-" + Ids.newId())));
        settle();
        clock.set(DAY.plusDays(1));
        tx.executeWithoutResult(s -> publisher.publishEvent(WithdrawalProcessedEvent.now(member.toString(), goal,
                Money.ofCents(12_000), "PAYOUT-" + Ids.newId())));
        settle();
        clock.set(DAY);
        assertThat(signals(member)).containsExactly("DEPOSIT_CHURN");
        assertThat(jdbc.sql("SELECT attributes ->> 'goalId' FROM fraud.fact WHERE member_id = :m AND kind = 'DEPOSIT'")
                .param("m", member).query(String.class).single()).isEqualTo(goal.toString());
    }

    @Test
    void goalEventsFromSavingsFeedVelocityAndAMemberIdThatIsNotAUuidStillMaps() {
        String legacyId = "member-" + UUID.randomUUID();
        UUID mapped = UUID.nameUUIDFromBytes(legacyId.getBytes(StandardCharsets.UTF_8));
        clock.set(DAY);
        for (int i = 0; i < 4; i++) {
            tx.executeWithoutResult(s -> publisher.publishEvent(GoalCreatedEvent.now(legacyId, Ids.newId(), "goal",
                    Money.ofCents(2_000), Money.ofCents(20_000))));
        }
        settle();
        assertThat(signals(mapped)).containsExactly("GOAL_VELOCITY");
    }

    @Test
    void aLoanRightAfterAccountCreationFiresAndAnOlderAccountDoesNot() {
        UUID fresh = member();
        observe(fresh, FactKind.ACCOUNT_CREATED, hoursAgo(10), "c1", null, null);
        observe(fresh, FactKind.APPLICATION_SUBMITTED, hoursAgo(1), "ap1", null, null);
        assertThat(signals(fresh)).containsExactly("NEW_ACCOUNT_LOAN");
        assertThat(fraud.flagOf(fresh)).isEmpty();

        UUID established = member();
        observe(established, FactKind.ACCOUNT_CREATED, hoursAgo(24 * 30), "c2", null, null);
        observe(established, FactKind.APPLICATION_SUBMITTED, hoursAgo(1), "ap2", null, null);
        assertThat(signals(established)).isEmpty();
    }

    @Test
    void aRealDisbursementTooSoonAfterFirstActivityFiresAndALaterOneDoesNot() {
        clock.set(DAY);
        UUID soon = member();
        observe(soon, FactKind.GOAL_CREATED, hoursAgo(10), "soon-g", null, null);
        disburse(soon, 300, 6, 15, DAY);
        settle();
        assertThat(signals(soon)).containsExactly("NEW_ACCOUNT_LOAN");

        UUID later = member();
        observe(later, FactKind.GOAL_CREATED, hoursAgo(24 * 20), "later-g", null, null);
        disburse(later, 300, 6, 15, DAY);
        settle();
        assertThat(signals(later)).isEmpty();
    }

    @Test
    void failedPaymentsAloneNeverRaiseAFlag() {
        UUID member = member();
        observe(member, FactKind.PAYMENT_FAILED, hoursAgo(24 * 10), "pf0", 10_000L, null);
        observe(member, FactKind.PAYMENT_FAILED, hoursAgo(24 * 5), "pf1", 10_000L, null);
        assertThat(signals(member)).isEmpty();
        observe(member, FactKind.PAYMENT_FAILED, hoursAgo(24), "pf2", 10_000L, null);
        observe(member, FactKind.PAYMENT_FAILED, hoursAgo(2), "pf3", 10_000L, null);
        assertThat(signals(member)).contains("REPEATED_FAILED_PAYMENTS");
        assertThat(weights(member)).allMatch(w -> w == 10);
        assertThat(fraud.flagOf(member)).as("a member who cannot pay needs help, not suspicion").isEmpty();
    }

    @Test
    void aPayoutRecipientChangedJustBeforeDisbursementFlagsAndAnOldChangeDoesNot() {
        clock.set(DAY);
        UUID changed = member();
        observe(changed, FactKind.PAYOUT_RECIPIENT_CHANGED, hoursAgo(24), "rc1", null, "new-account-" + changed);
        disburse(changed, 300, 6, 15, DAY);
        settle();
        assertThat(signals(changed)).contains("PAYOUT_RECIPIENT_CHANGE");
        FlagView flag = fraud.flagOf(changed).orElseThrow();
        assertThat(flag.status()).isEqualTo(FlagStatus.OPEN);
        assertThat(flag.score()).isGreaterThanOrEqualTo(40);

        UUID steady = member();
        observe(steady, FactKind.PAYOUT_RECIPIENT_CHANGED, hoursAgo(24 * 10), "rc2", null, "old-account-" + steady);
        disburse(steady, 300, 6, 15, DAY);
        settle();
        assertThat(signals(steady)).doesNotContain("PAYOUT_RECIPIENT_CHANGE");
        assertThat(fraud.flagOf(steady)).isEmpty();
    }

    // ---- the facts only the wiring in app can feed: registration, applications, payout destination ----------------

    @Test
    void aRegistrationReportedByIdentityIsTheAccountStartAndIsRecordedOnce() {
        clock.set(DAY.minusDays(30));
        UUID member = register();
        settle();
        assertThat(kinds(member)).containsExactly("ACCOUNT_CREATED");
        assertThat(jdbc.sql("SELECT occurred_at FROM fraud.fact WHERE member_id = :m AND kind = 'ACCOUNT_CREATED'").param("m", member)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(clock.now());

        // The same registration delivered again, even under another event id, adds nothing.
        tx.executeWithoutResult(s -> publisher.publishEvent(new MemberRegistered(Ids.newId(), member, Language.EN, clock.now())));
        settle();
        assertThat(kinds(member)).containsExactly("ACCOUNT_CREATED");
    }

    @Test
    void anOldAccountThatAppliesRightAfterItsFirstGoalIsNotAFalsePositive() throws Exception {
        clock.set(DAY.minusDays(30));
        UUID member = register();
        settle();
        clock.set(DAY);
        // Without the registration, the goal below would be the first thing Seed saw and the application hours later would
        // look like a new account (the false positive of ADR 0019).
        LoanApplicationView application = applyForALoan(member);
        settle();

        assertThat(kinds(member)).containsExactlyInAnyOrder("ACCOUNT_CREATED", "GOAL_CREATED", "DEPOSIT", "DEPOSIT", "DEPOSIT", "DEPOSIT",
                "APPLICATION_SUBMITTED");
        assertThat(signals(member)).doesNotContain("NEW_ACCOUNT_LOAN");
        assertThat(fraud.flagOf(member)).isEmpty();

        // Later states of the same application are not new applications.
        microloans.approveApplication(application.id(), UUID.randomUUID());
        settle();
        assertThat(kinds(member).stream().filter("APPLICATION_SUBMITTED"::equals)).hasSize(1);
        assertThat(jdbc.sql("SELECT amount_cents FROM fraud.fact WHERE member_id = :m AND kind = 'APPLICATION_SUBMITTED'").param("m", member)
                .query(Long.class).single()).isEqualTo(120_000L);
    }

    @Test
    void aBrandNewAccountThatAppliesAtOnceFiresWithTheRegistrationAsItsBasis() throws Exception {
        clock.set(DAY);
        UUID member = register();
        settle();
        applyForALoan(member);
        settle();

        assertThat(signals(member)).containsExactly("NEW_ACCOUNT_LOAN");
        assertThat(jdbc.sql("SELECT evidence ->> 'accountStartBasis' FROM fraud.signal WHERE member_id = :m").param("m", member)
                .query(String.class).single()).isEqualTo("REGISTERED");
        assertThat(fraud.flagOf(member)).as("35 points is advice-free: one mild signal alone flags nobody").isEmpty();
    }

    @Test
    void changingTheWalletJustBeforeALoanFlagsButALanguageChangeAndAnOldChangeDoNot() {
        clock.set(DAY.minusDays(10));
        UUID changed = register();
        UUID steady = register();
        settle();
        assertThat(kinds(changed)).as("the destination at registration is a baseline, not a change").doesNotContain("PAYOUT_RECIPIENT_CHANGED");

        // A different language leaves the destination where it was.
        identity.updateProfile(changed, new ProfileUpdate(Language.ES, null, null));
        settle();
        assertThat(kinds(changed)).doesNotContain("PAYOUT_RECIPIENT_CHANGED");

        // steady switches to Venmo nine days before the loan; changed switches on the day.
        clock.set(DAY.minusDays(9));
        identity.updateProfile(steady, new ProfileUpdate(null, WalletPreference.VENMO, null));
        settle();
        clock.set(DAY);
        identity.updateProfile(changed, new ProfileUpdate(null, WalletPreference.VENMO, null));
        identity.updateProfile(changed, new ProfileUpdate(null, WalletPreference.VENMO, null));      // unchanged: no second event
        settle();
        assertThat(kinds(changed)).filteredOn("PAYOUT_RECIPIENT_CHANGED"::equals).hasSize(1);
        assertThat(kinds(steady)).filteredOn("PAYOUT_RECIPIENT_CHANGED"::equals).hasSize(1);

        disburse(changed, 300, 6, 15, DAY);
        disburse(steady, 300, 6, 15, DAY);
        settle();

        assertThat(signals(changed)).contains("PAYOUT_RECIPIENT_CHANGE");
        assertThat(fraud.flagOf(changed)).get().extracting(FlagView::status).isEqualTo(FlagStatus.OPEN);
        assertThat(signals(steady)).doesNotContain("PAYOUT_RECIPIENT_CHANGE");
        assertThat(fraud.flagOf(steady)).isEmpty();
    }

    @Test
    void thePayoutDestinationIsOnlyEverStoredAsAHash() {
        clock.set(DAY);
        UUID member = register();
        identity.updateProfile(member, new ProfileUpdate(null, WalletPreference.VENMO, null));
        settle();
        String payer = identity.paypalPayerId(member).orElseThrow();

        List<String> stored = new ArrayList<>(jdbc.sql("SELECT ref_hash FROM fraud.fact WHERE member_id = :m AND ref_hash IS NOT NULL")
                .param("m", member).query(String.class).list());
        stored.addAll(jdbc.sql("SELECT ref_hash FROM fraud.payout_destination WHERE member_id = :m").param("m", member).query(String.class).list());
        assertThat(stored).isNotEmpty().allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(String.join(" ", stored).toLowerCase()).doesNotContain(payer.toLowerCase()).doesNotContain("example");
    }

    // ---- facts, flags and what they are not ---------------------------------------------------------------------

    @Test
    void anObservationIsIdempotentAndKeepsOnlyAllowedAttributes() {
        UUID member = member();
        Observation o = new Observation(member, FactKind.DEPOSIT, hoursAgo(3), "once-" + member, 4_000L, "SomeRef-" + member,
                Map.of("goalId", "goal-1", "email", "a@b.com", "name", "Ana Perez"));
        fraud.observe(o);
        fraud.observe(o);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fraud.fact WHERE member_id = :m").param("m", member).query(Integer.class).single())
                .isEqualTo(1);
        String attributes = jdbc.sql("SELECT attributes::text FROM fraud.fact WHERE member_id = :m").param("m", member)
                .query(String.class).single();
        assertThat(attributes).contains("goal-1").doesNotContain("a@b.com").doesNotContain("Ana");
    }

    @Test
    void aFlagBlocksNobodyAndIsRaisedWithAnEventAndAnAuditEntry() throws Exception {
        UUID member = member();
        flagByShortBurst(member);
        FlagView flag = fraud.flagOf(member).orElseThrow();
        assertThat(published.stream(FraudFlagRaised.class).filter(e -> e.flagId().equals(flag.flagId())).toList())
                .singleElement().satisfies(e -> {
                    assertThat(e.memberId()).isEqualTo(member);
                    assertThat(e.score()).isEqualTo(flag.score());
                    assertThat(e.signalCodes()).containsExactlyInAnyOrder("GOAL_VELOCITY", "APPLICATION_VELOCITY");
                });
        assertThat(jdbc.sql("SELECT actor_id FROM governance.audit_entry WHERE entry_type = 'FRAUD_FLAG_RAISED' AND subject_id = :id")
                .param("id", flag.flagId()).query(String.class).single()).isEqualTo("fraud-rules");

        // The member can still use Seed as before: nothing reads the flag to refuse them.
        mvc.perform(get("/api/v1/loans").header("X-Seed-Member-Id", member.toString())).andExpect(status().isOk());
        assertThat(fraud.openFlags()).extracting(FlagView::flagId).contains(flag.flagId());
    }

    @Test
    void anOfficerReviewsOnceAndItIsAuditedAndTheEvidenceIsAvailable() throws Exception {
        UUID member = member();
        flagByShortBurst(member);
        UUID flagId = fraud.flagOf(member).orElseThrow().flagId();
        UUID officer = UUID.randomUUID();

        JsonNode detail = body(staff(get("/api/v1/desk/fraud/flags/{id}", flagId), officer, "CHECKER"), 200);
        assertThat(detail.path("policyVersion").asText()).isEqualTo("fraud-rules-v1");
        assertThat(detail.path("flagThreshold").asInt()).isEqualTo(40);
        assertThat(detail.path("flag").path("status").asText()).isEqualTo("OPEN");
        assertThat(detail.path("signals")).hasSize(2);
        assertThat(detail.path("signals").get(0).path("explanation").asText()).isNotBlank();

        JsonNode evidence = body(staff(get("/api/v1/desk/fraud/flags/{id}/evidence", flagId), officer, "OFFICER"), 200);
        assertThat(evidence.path("flagId").asText()).isEqualTo(flagId.toString());
        assertThat(evidence.path("signals")).hasSize(2);
        for (JsonNode signal : evidence.path("signals")) {
            assertThat(signal.path("signal").path("weight").asInt()).isPositive();
            assertThat(signal.path("facts")).isNotEmpty();
            signal.path("facts").forEach(f -> assertThat(f.path("kind").asText()).isIn("GOAL_CREATED", "APPLICATION_SUBMITTED"));
        }
        assertThat(evidence.toString()).doesNotContain("email");

        review(flagId, officer, "CHECKER", "CLEAR", "wrong-role").andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/desk/fraud/flags/{id}/review", flagId).header("X-Seed-Member-Id", member.toString())
                        .header("Idempotency-Key", "member").contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CLEAR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/desk/fraud/flags/{id}/review", flagId).header("Idempotency-Key", "anon")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CLEAR\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(staff(post("/api/v1/desk/fraud/flags/{id}/review", flagId), officer, "OFFICER").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CLEAR\"}"))
                .andExpect(status().isBadRequest());
        review(flagId, officer, "OFFICER", "CONFIRM", "no-note", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("fraud-note-required"));
        assertThat(fraud.flagOf(member).orElseThrow().status()).as("nothing changed yet").isEqualTo(FlagStatus.OPEN);

        review(flagId, officer, "OFFICER", "CLEAR", "clear-1", "Two siblings saving separately; same household.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEWED_CLEAR"))
                .andExpect(jsonPath("$.reviewedAt").exists());
        review(flagId, officer, "OFFICER", "CLEAR", "clear-1", "Two siblings saving separately; same household.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEWED_CLEAR"));
        review(flagId, officer, "OFFICER", "CONFIRM", "clear-1", "Changed my mind").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));
        review(flagId, UUID.randomUUID(), "OFFICER", "CONFIRM", "other-key", "Changed my mind").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("fraud-flag-already-reviewed"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM governance.audit_entry WHERE entry_type = 'FRAUD_FLAG_REVIEWED' AND subject_id = :id AND actor_id = :a")
                .param("id", flagId).param("a", officer.toString()).query(Integer.class).single()).as("one entry, by the officer").isEqualTo(1);
        String payload = jdbc.sql("SELECT payload::text FROM governance.audit_entry WHERE entry_type = 'FRAUD_FLAG_REVIEWED' AND subject_id = :id")
                .param("id", flagId).query(String.class).single();
        assertThat(payload).contains("REVIEWED_CLEAR").doesNotContain("siblings");
        assertThat(governance.verifyChain().intact()).isTrue();
        assertThat(published.stream(FraudFlagReviewed.class).filter(e -> e.flagId().equals(flagId)).toList()).singleElement()
                .satisfies(e -> assertThat(e.outcome()).isEqualTo(FlagStatus.REVIEWED_CLEAR));

        // A cleared flag stays cleared: the same evidence, seen again, opens nothing.
        observe(member, FactKind.DEPOSIT, hoursAgo(1), "after-clear", 1_000L, null);
        assertThat(fraud.openFlags()).extracting(FlagView::flagId).doesNotContain(flagId);
        assertThat(fraud.flagOf(member).orElseThrow().status()).isEqualTo(FlagStatus.REVIEWED_CLEAR);
        assertThat(fraud.openFlags()).extracting(FlagView::memberId).doesNotContain(member);

        // New evidence is a new finding and may raise a new flag.
        observe(member, FactKind.GOAL_CREATED, hoursAgo(0), "again-g", null, null);
        observe(member, FactKind.APPLICATION_SUBMITTED, hoursAgo(0), "again-a", null, null);
        FlagView second = fraud.flagOf(member).orElseThrow();
        assertThat(second.status()).isEqualTo(FlagStatus.OPEN);
        assertThat(second.flagId()).isNotEqualTo(flagId);
    }

    @Test
    void confirmingNeedsANoteAndRecordsIt() throws Exception {
        UUID member = member();
        flagByShortBurst(member);
        UUID flagId = fraud.flagOf(member).orElseThrow().flagId();
        review(flagId, UUID.randomUUID(), "ADMIN", "CONFIRM", "confirm-1", "Same payer on three accounts, confirmed by phone.")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        JsonNode detail = body(staff(get("/api/v1/desk/fraud/flags/{id}", flagId), UUID.randomUUID(), "OFFICER"), 200);
        assertThat(detail.path("review").path("decision").asText()).isEqualTo("CONFIRM");
        assertThat(detail.path("review").path("note").asText()).contains("confirmed by phone");
    }

    @Test
    void theDeskRoutesAreForOfficersOnlyAndUnknownFlagsAre404() throws Exception {
        UUID flagId = UUID.randomUUID();
        for (String path : List.of("/api/v1/desk/fraud/flags", "/api/v1/desk/fraud/flags/" + flagId,
                "/api/v1/desk/fraud/flags/" + flagId + "/evidence")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("X-Seed-Member-Id", UUID.randomUUID().toString())).andExpect(status().isForbidden());
            mvc.perform(staff(get(path), UUID.randomUUID(), "")).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", SERVICE_TOKEN)).andExpect(status().isUnauthorized()); // the service token opens /internal only
        }
        mvc.perform(staff(get("/api/v1/desk/fraud/flags/{id}", flagId), UUID.randomUUID(), "OFFICER")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fraud-flag-not-found"));
        mvc.perform(staff(get("/api/v1/desk/fraud/flags/{id}/evidence", flagId), UUID.randomUUID(), "OFFICER"))
                .andExpect(status().isNotFound());
        review(flagId, UUID.randomUUID(), "OFFICER", "CLEAR", "nope").andExpect(status().isNotFound());
        mvc.perform(staff(get("/api/v1/desk/fraud/flags"), UUID.randomUUID(), "OFFICER").param("status", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void flagsArePagedNewestFirstWithAKeysetCursorAndAStatusFilter() throws Exception {
        List<UUID> flagIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID member = member();
            flagByShortBurst(member);
            flagIds.add(fraud.flagOf(member).orElseThrow().flagId());
            Thread.sleep(3);
        }
        review(flagIds.get(1), UUID.randomUUID(), "OFFICER", "CLEAR", "page-clear").andExpect(status().isOk());

        JsonNode open = body(staff(get("/api/v1/desk/fraud/flags").param("status", "OPEN").param("limit", "2"), UUID.randomUUID(), "OFFICER"), 200);
        assertThat(open.path("rows")).hasSize(2);
        assertThat(open.path("rows").get(0).path("flagId").asText()).isEqualTo(flagIds.get(2).toString());
        assertThat(open.path("rows").get(1).path("flagId").asText()).isEqualTo(flagIds.get(0).toString());
        assertThat(open.path("rows").get(0).path("signalCodes")).isNotEmpty();
        assertThat(open.path("rows").get(0).fieldNames()).toIterable().doesNotContain("note", "staffId");

        JsonNode cleared = body(staff(get("/api/v1/desk/fraud/flags").param("status", "REVIEWED_CLEAR"), UUID.randomUUID(), "CHECKER"), 200);
        assertThat(cleared.findValuesAsText("flagId")).contains(flagIds.get(1).toString()).doesNotContain(flagIds.get(0).toString());

        JsonNode firstPage = body(staff(get("/api/v1/desk/fraud/flags").param("limit", "1"), UUID.randomUUID(), "ADMIN"), 200);
        UUID cursor = UUID.fromString(firstPage.path("nextBefore").asText());
        JsonNode secondPage = body(staff(get("/api/v1/desk/fraud/flags").param("limit", "1").param("before", cursor.toString()),
                UUID.randomUUID(), "ADMIN"), 200);
        assertThat(secondPage.path("rows").get(0).path("flagId").asText()).isNotEqualTo(firstPage.path("rows").get(0).path("flagId").asText());
    }

    @Test
    void overlappingObservationsOfOneSharedPayerNeitherDeadlockNorLoseAFlag() throws Exception {
        String payer = "RACE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<UUID> members = List.of(member(), member(), member(), member());
        ExecutorService pool = Executors.newFixedThreadPool(members.size());
        try {
            List<Future<?>> done = new ArrayList<>();
            for (UUID m : members) {
                done.add(pool.submit(() -> observe(m, FactKind.PAYER_SEEN, hoursAgo(5), "race-" + m, null, payer)));
            }
            for (Future<?> f : done) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID m : members) {
            assertThat(signals(m)).contains("SHARED_PAYER");
            assertThat(fraud.flagOf(m)).isPresent();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM fraud.flag WHERE member_id = :m AND status = 'OPEN'").param("m", m)
                    .query(Integer.class).single()).isEqualTo(1);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private void flagByShortBurst(UUID member) {
        String tag = member.toString().substring(0, 8);
        observe(member, FactKind.ACCOUNT_CREATED, hoursAgo(24 * 30), "burst-acct" + tag, null, null);
        for (int i = 0; i < 4; i++) {
            observe(member, FactKind.GOAL_CREATED, hoursAgo(6 - i), "burst-g" + i + tag, null, null);
        }
        observe(member, FactKind.APPLICATION_SUBMITTED, hoursAgo(3), "burst-a1" + tag, null, null);
        observe(member, FactKind.APPLICATION_SUBMITTED, hoursAgo(2), "burst-a2" + tag, null, null);
        assertThat(fraud.flagOf(member)).isPresent();
    }

    private UUID member() {
        return UUID.randomUUID();
    }

    /** A member who signed in with PayPal, so identity publishes a real registration. */
    private UUID register() {
        return identity.loginWithPayPal(new PayPalLogin("PAYER-" + UUID.randomUUID(), "who-" + UUID.randomUUID() + "@example.test", "Who", true, "en"))
                .member().memberId();
    }

    /** Saves four months' worth in one month, as the proven flows do, then applies for $1,200 over eight months. */
    private LoanApplicationView applyForALoan(UUID member) {
        UUID goal = createGoal.execute(new CreateGoalUseCase.Command(member.toString(), "Commercial stand mixer", Money.usd(1200.00),
                Money.usd(75.00), LocalDate.of(2027, 6, 1), "sub-" + member)).getId();
        for (int i = 1; i <= 4; i++) {
            deposits.execute(new RecordDepositUseCase.Command(goal, Money.usd(75.00), "cap-" + i + "-" + member));
        }
        fairCheck.grantConsent(member);
        return microloans.submitApplication(member, new SubmitApplicationCommand("Commercial stand mixer", 120_000L, 8, 15, WalletType.PAYPAL,
                true, OptionalLong.of(165_000L)));
    }

    private List<String> kinds(UUID member) {
        return jdbc.sql("SELECT kind FROM fraud.fact WHERE member_id = :m ORDER BY id").param("m", member).query(String.class).list();
    }

    private Instant hoursAgo(long hours) {
        clock.set(DAY);
        return clock.now().minus(Duration.ofHours(hours));
    }

    private void observe(UUID member, FactKind kind, Instant at, String key, Long amount, String reference) {
        fraud.observe(new Observation(member, kind, at, key + ":" + member, amount, reference, Map.of()));
    }

    private List<String> signals(UUID member) {
        return jdbc.sql("SELECT code FROM fraud.signal WHERE member_id = :m ORDER BY id").param("m", member).query(String.class).list();
    }

    private List<Integer> weights(UUID member) {
        return jdbc.sql("SELECT weight FROM fraud.signal WHERE member_id = :m ORDER BY id").param("m", member).query(Integer.class).list();
    }

    private ResultActions review(UUID flagId, UUID staffId, String roles, String decision, String key) throws Exception {
        return review(flagId, staffId, roles, decision, key, "A note.");
    }

    private ResultActions review(UUID flagId, UUID staffId, String roles, String decision, String key, String note) throws Exception {
        String body = note == null ? "{\"decision\":\"" + decision + "\"}"
                : "{\"decision\":\"" + decision + "\",\"note\":\"" + note + "\"}";
        return mvc.perform(staff(post("/api/v1/desk/fraud/flags/{id}/review", flagId), staffId, roles).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder request, UUID staffId, String roles) {
        return request.header("X-Seed-Staff-Id", staffId.toString()).header("X-Seed-Staff-Roles", roles);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
