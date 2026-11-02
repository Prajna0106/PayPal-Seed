package com.paypalseed.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.dto.IdentityDtos.PayoutRecipient;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.domain.model.ConsentEntry;
import com.paypalseed.identity.domain.model.ConsentLedger;
import com.paypalseed.identity.domain.model.ConsentLedger.Step;
import com.paypalseed.identity.domain.model.IdentityErrors;
import com.paypalseed.identity.domain.model.Member;
import com.paypalseed.identity.domain.model.StaffMember;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.security.Actor.Role;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of identity that need no database: languages, login merging, profile edits, staff roles, the consent ledger. */
class IdentityUnitTest {

    private static final Instant T0 = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    // language

    @Test
    void languageAcceptsOnlyEnglishAndSpanish() {
        assertThat(Language.parse("es")).contains(Language.ES);
        assertThat(Language.parse(" EN ")).contains(Language.EN);
        assertThat(Language.parse("fr")).isEmpty();
        assertThat(Language.parse("es-MX")).isEmpty();
        assertThat(Language.parse(null)).isEmpty();
    }

    @Test
    void localeUsesOnlyTheLanguagePartAndIgnoresUnsupportedOnes() {
        assertThat(Language.fromLocale("es-MX")).contains(Language.ES);
        assertThat(Language.fromLocale("en_US")).contains(Language.EN);
        assertThat(Language.fromLocale("pt-BR")).isEmpty();
        assertThat(Language.fromLocale("  ")).isEmpty();
        assertThat(Language.fromLocale(null)).isEmpty();
    }

    @Test
    void walletAndConsentTypesParseCaseInsensitively() {
        assertThat(WalletPreference.parse("venmo")).contains(WalletPreference.VENMO);
        assertThat(WalletPreference.parse("cash")).isEmpty();
        assertThat(ConsentType.parse("income_read")).contains(ConsentType.INCOME_READ);
        assertThat(ConsentType.parse("marketing")).isEmpty();
    }

    // login merging

    @Test
    void registrationDefaultsToEnglishAndPaypalWallet() {
        Member m = Member.register(MEMBER, " PAYER1 ", "ana@example.org", "Ana", true, null, T0);

        assertThat(m.paypalPayerId()).isEqualTo("PAYER1");
        assertThat(m.language()).isEqualTo(Language.EN);
        assertThat(m.walletPreference()).isEqualTo(WalletPreference.PAYPAL);
        assertThat(m.paypalVerified()).isTrue();
        assertThat(m.createdAt()).isEqualTo(m.lastLoginAt()).isEqualTo(T0);
    }

    @Test
    void registrationNeedsAPayerId() {
        assertThatThrownBy(() -> Member.register(MEMBER, "  ", null, null, null, null, T0))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(IdentityErrors.INVALID_PAYER_ID));
        assertThatThrownBy(() -> Member.register(MEMBER, "x".repeat(65), null, null, null, null, T0))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void laterLoginKeepsStoredValuesThatPayPalDidNotSend() {
        Member first = Member.register(MEMBER, "P1", "ana@example.org", "Ana", true, Language.ES, T0);

        Member again = first.afterLogin(null, " ", null, null, T1);

        assertThat(again.email()).isEqualTo("ana@example.org");
        assertThat(again.displayName()).isEqualTo("Ana");
        assertThat(again.paypalVerified()).isTrue();
        assertThat(again.language()).as("no locale sent: never downgraded to the default").isEqualTo(Language.ES);
        assertThat(again.lastLoginAt()).isEqualTo(T1);
        assertThat(again.createdAt()).isEqualTo(T0);
        assertThat(again.id()).isEqualTo(first.id());
    }

    @Test
    void laterLoginReplacesWhatPayPalSendsIncludingAProvidedLanguage() {
        Member first = Member.register(MEMBER, "P1", "ana@example.org", "Ana", false, null, T0);

        Member again = first.afterLogin("ana@new.example.org", "Ana M", true, Language.ES, T1);

        assertThat(again.email()).isEqualTo("ana@new.example.org");
        assertThat(again.displayName()).isEqualTo("Ana M");
        assertThat(again.paypalVerified()).isTrue();
        assertThat(again.language()).isEqualTo(Language.ES);
        assertThat(first.sameProfileChoices(again)).isFalse();
    }

    // profile edits

    @Test
    void profileEditChangesOnlyTheGivenFields() {
        Member m = Member.register(MEMBER, "P1", "ana@example.org", "Ana", true, null, T0);

        Member edited = m.withProfile(Language.ES, null, null, T1);

        assertThat(edited.language()).isEqualTo(Language.ES);
        assertThat(edited.walletPreference()).isEqualTo(WalletPreference.PAYPAL);
        assertThat(edited.displayName()).isEqualTo("Ana");
        assertThat(edited.updatedAt()).isEqualTo(T1);
        assertThat(edited.lastLoginAt()).isEqualTo(T0);
    }

    @Test
    void profileEditWithNothingNewReturnsTheSameValue() {
        Member m = Member.register(MEMBER, "P1", null, "Ana", true, Language.ES, T0);

        assertThat(m.withProfile(Language.ES, WalletPreference.PAYPAL, "Ana", T1)).isSameAs(m);
        assertThat(m.withProfile(null, null, null, T1)).isSameAs(m);
    }

    @Test
    void displayNameCannotBeEmptied() {
        Member m = Member.register(MEMBER, "P1", null, "Ana", true, null, T0);

        assertThatThrownBy(() -> m.withProfile(null, null, "   ", T1)).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.errorCode()).isEqualTo(IdentityErrors.INVALID_NAME));
    }

    // staff

    @Test
    void staffNeedsAtLeastOneStaffRole() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> StaffMember.of(id, "Aira", null, Set.of(), true, T0, T0)).isInstanceOfSatisfying(
                DomainException.class, e -> assertThat(e.errorCode()).isEqualTo(IdentityErrors.INVALID_STAFF_ROLES));
        assertThatThrownBy(() -> StaffMember.of(id, "Aira", null, Set.of(Role.MEMBER), true, T0, T0))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StaffMember.of(id, "Aira", null, Set.of(Role.OFFICER, Role.SERVICE), true, T0, T0))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> StaffMember.of(id, " ", null, Set.of(Role.OFFICER), true, T0, T0))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(IdentityErrors.INVALID_NAME));
    }

    @Test
    void staffIsActiveUnlessToldOtherwiseAndBlankEmailIsNone() {
        StaffMember s = StaffMember.of(UUID.randomUUID(), " Aira ", " ", Set.of(Role.OFFICER, Role.CHECKER), null, T0, T1);

        assertThat(s.active()).isTrue();
        assertThat(s.displayName()).isEqualTo("Aira");
        assertThat(s.email()).isNull();
        assertThat(s.roles()).containsExactlyInAnyOrder(Role.OFFICER, Role.CHECKER);
        assertThat(s.createdAt()).isEqualTo(T0);
        assertThat(s.updatedAt()).isEqualTo(T1);
    }

    // consent ledger

    private static ConsentEntry open(ConsentType type, String version) {
        return new ConsentEntry(UUID.randomUUID(), MEMBER, type, version, T0, null);
    }

    @Test
    void firstGrantAppends() {
        assertThat(new ConsentLedger(List.of()).stepForGrant(ConsentType.TERMS, "v1")).isEqualTo(Step.APPEND);
    }

    @Test
    void grantingTheSameVersionAgainChangesNothing() {
        ConsentLedger ledger = new ConsentLedger(List.of(open(ConsentType.TERMS, "v1")));

        assertThat(ledger.stepForGrant(ConsentType.TERMS, "v1")).isEqualTo(Step.ALREADY_ACTIVE);
    }

    @Test
    void aNewVersionClosesTheOldLineAndAppends() {
        ConsentLedger ledger = new ConsentLedger(List.of(open(ConsentType.TERMS, "v1")));

        assertThat(ledger.stepForGrant(ConsentType.TERMS, "v2")).isEqualTo(Step.CLOSE_AND_APPEND);
    }

    @Test
    void aConsentOfAnotherTypeIsIndependent() {
        ConsentLedger ledger = new ConsentLedger(List.of(open(ConsentType.TERMS, "v1")));

        assertThat(ledger.stepForGrant(ConsentType.INCOME_READ, "v1")).isEqualTo(Step.APPEND);
        assertThat(ledger.active(ConsentType.INCOME_READ)).isEmpty();
    }

    @Test
    void aRevokedLineIsHistoryAndAGrantAfterItAppends() {
        ConsentEntry revoked = open(ConsentType.RECORD_SHARING, "v1").closedAt(T1);
        ConsentLedger ledger = new ConsentLedger(List.of(revoked));

        assertThat(ledger.active(ConsentType.RECORD_SHARING)).isEmpty();
        assertThat(ledger.stepForGrant(ConsentType.RECORD_SHARING, "v1")).isEqualTo(Step.APPEND);
    }

    @Test
    void closingALineChangesOnlyItsRevocationTimeAndOnlyOnce() {
        ConsentEntry line = open(ConsentType.PRIVACY, "v1");

        ConsentEntry closed = line.closedAt(T1);

        assertThat(closed.revokedAt()).isEqualTo(T1);
        assertThat(closed).usingRecursiveComparison().ignoringFields("revokedAt").isEqualTo(line);
        assertThat(line.active()).as("the original value is untouched").isTrue();
        assertThatThrownBy(() -> closed.closedAt(T1.plusSeconds(5))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aClockThatReadsEarlierThanTheGrantNeverBlocksARevocation() {
        ConsentEntry closed = open(ConsentType.TERMS, "v1").closedAt(T0.minusSeconds(3600));

        assertThat(closed.revokedAt()).isEqualTo(T0);
    }

    @Test
    void payoutRecipientHoldsThePayerIdAndEmailFromTheLoginRecordAndPrintsNeither() {
        Member m = Member.register(MEMBER, "PAYER-9", "ana@example.org", "Ana", true, null, T0);
        PayoutRecipient recipient = new PayoutRecipient(m.paypalPayerId(), m.email());

        assertThat(recipient.payerId()).isEqualTo("PAYER-9");
        assertThat(recipient.email()).isEqualTo("ana@example.org");
        assertThat(recipient.toString()).doesNotContain("PAYER-9", "ana@example.org");
        // a later login that sends no email keeps the stored one, so the recipient stays complete
        assertThat(m.afterLogin(null, null, null, null, T1).email()).isEqualTo("ana@example.org");
    }

    // personal data stays out of strings

    @Test
    void valuesThatHoldPersonalDataNeverPrintIt() {
        Member m = Member.register(MEMBER, "PAYER-SECRET", "ana@example.org", "Ana Secret", true, null, T0);
        String printed = String.join(" ", m.toString(),
                new PayPalLogin("PAYER-SECRET", "ana@example.org", "Ana Secret", true, "en").toString(),
                new MemberView(MEMBER, "ana@example.org", "Ana Secret", Language.EN, WalletPreference.PAYPAL, true, T0, T0).toString(),
                new StaffView(UUID.randomUUID(), "Ana Secret", "ana@example.org", List.of(Role.OFFICER), true).toString(),
                StaffMember.of(UUID.randomUUID(), "Ana Secret", "ana@example.org", Set.of(Role.OFFICER), true, T0, T0).toString());

        assertThat(printed).doesNotContain("ana@example.org", "Ana Secret", "PAYER-SECRET");
    }
}
