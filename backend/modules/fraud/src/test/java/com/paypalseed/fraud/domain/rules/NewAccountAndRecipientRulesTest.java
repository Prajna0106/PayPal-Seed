package com.paypalseed.fraud.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.paypalseed.fraud.api.dto.FraudDtos.FactKind;
import com.paypalseed.fraud.domain.model.Signal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NewAccountAndRecipientRulesTest {

    private final NewAccountLoanRule newAccount = new NewAccountLoanRule();
    private final RecipientChangeRule recipient = new RecipientChangeRule();

    @Test
    void anApplicationHoursAfterRegistrationFires() {
        Scenario s = new Scenario();
        long created = s.add(FactKind.ACCOUNT_CREATED, Duration.ZERO);
        long applied = s.add(FactKind.APPLICATION_SUBMITTED, Duration.ofHours(10));
        Signal signal = newAccount.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("NEW_ACCOUNT_LOAN");
        assertThat(signal.weight()).isEqualTo(35);
        assertThat(signal.factIds()).containsExactly(created, applied);
        assertThat(signal.evidence()).containsEntry("accountAgeHours", 10L).containsEntry("accountStartBasis", "REGISTERED")
                .containsEntry("loanFact", "APPLICATION_SUBMITTED");
    }

    @Test
    void anOlderAccountIsFine() {
        Scenario s = new Scenario();
        s.add(FactKind.ACCOUNT_CREATED, Duration.ZERO);
        s.add(FactKind.APPLICATION_SUBMITTED, Duration.ofHours(72).plusSeconds(1));
        assertThat(newAccount.evaluate(s.context())).isEmpty();

        Scenario boundary = new Scenario();
        boundary.add(FactKind.ACCOUNT_CREATED, Duration.ZERO);
        boundary.add(FactKind.APPLICATION_SUBMITTED, Duration.ofHours(72));
        assertThat(newAccount.evaluate(boundary.context())).isPresent();
    }

    @Test
    void withoutARegistrationTheFirstOtherFactMarksTheStart() {
        Scenario s = new Scenario();
        s.add(FactKind.GOAL_CREATED, Duration.ZERO);
        s.add(FactKind.LOAN_DISBURSED, Duration.ofHours(30), 60_000L);
        Signal signal = newAccount.evaluate(s.context()).orElseThrow();
        assertThat(signal.evidence()).containsEntry("accountStartBasis", "FIRST_SEEN").containsEntry("loanFact", "LOAN_DISBURSED");
    }

    @Test
    void aLoanFactThatIsTheFirstThingSeenProvesNothingAboutAge() {
        Scenario s = new Scenario();
        s.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        assertThat(newAccount.evaluate(s.context())).isEmpty();
        assertThat(newAccount.evaluate(new Scenario().context())).isEmpty();

        Scenario onlyLoanFacts = new Scenario();
        onlyLoanFacts.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        onlyLoanFacts.add(FactKind.LOAN_DISBURSED, Duration.ofHours(1));
        assertThat(newAccount.evaluate(onlyLoanFacts.context())).isEmpty();
    }

    @Test
    void aLoanFactBeforeTheRegistrationRecordIsNotCounted() {
        Scenario s = new Scenario();
        s.add(FactKind.APPLICATION_SUBMITTED, Duration.ZERO);
        s.add(FactKind.ACCOUNT_CREATED, Duration.ofDays(5));
        assertThat(newAccount.evaluate(s.context())).isEmpty();
    }

    @Test
    void aNewRecipientJustBeforeDisbursementFires() {
        Scenario s = new Scenario();
        long changed = s.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ZERO, null, Scenario.hash('e'));
        long paid = s.add(FactKind.LOAN_DISBURSED, Duration.ofHours(24), 60_000L);
        Signal signal = recipient.evaluate(s.context()).orElseThrow();
        assertThat(signal.code()).isEqualTo("PAYOUT_RECIPIENT_CHANGE");
        assertThat(signal.weight()).isEqualTo(40);
        assertThat(signal.factIds()).containsExactly(changed, paid);
        assertThat(signal.evidence()).containsEntry("hoursBeforeDisbursement", 24L).containsEntry("disbursements", 1);
    }

    @Test
    void anOldChangeOrALaterChangeIsFine() {
        Scenario old = new Scenario();
        old.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ZERO);
        old.add(FactKind.LOAN_DISBURSED, Duration.ofHours(72).plusSeconds(1), 60_000L);
        assertThat(recipient.evaluate(old.context())).isEmpty();

        Scenario after = new Scenario();
        after.add(FactKind.LOAN_DISBURSED, Duration.ZERO, 60_000L);
        after.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ofHours(2));
        assertThat(recipient.evaluate(after.context())).isEmpty();

        Scenario boundary = new Scenario();
        boundary.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ZERO);
        boundary.add(FactKind.LOAN_DISBURSED, Duration.ofHours(72), 60_000L);
        assertThat(recipient.evaluate(boundary.context())).isPresent();
        assertThat(recipient.evaluate(new Scenario().context())).isEmpty();
    }

    @Test
    void theClosestChangeBeforeEachDisbursementIsUsed() {
        Scenario s = new Scenario();
        s.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ZERO);
        long recent = s.add(FactKind.PAYOUT_RECIPIENT_CHANGED, Duration.ofHours(40));
        long paid = s.add(FactKind.LOAN_DISBURSED, Duration.ofHours(48), 60_000L);
        Signal signal = recipient.evaluate(s.context()).orElseThrow();
        assertThat(signal.factIds()).containsExactly(recent, paid);
        assertThat(signal.evidence()).containsEntry("hoursBeforeDisbursement", 8L);
    }
}
