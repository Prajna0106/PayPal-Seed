package com.paypalseed.governance.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.governance.domain.model.AuditHash;
import com.paypalseed.governance.domain.model.StaffRefs;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GovernanceUnitTest {

    @Test
    void canonicalJsonSortsKeysAndDropsWhitespace() {
        assertThat(CanonicalJson.of("{ \"b\": 1, \"a\": { \"d\": [3, 2], \"c\": null } }"))
                .isEqualTo("{\"a\":{\"c\":null,\"d\":[3,2]},\"b\":1}");
    }

    /** Postgres jsonb expands exponents and keeps scale; the canonical form must agree with what it returns. */
    @Test
    void canonicalNumbersMatchWhatJsonbReturns() {
        assertThat(CanonicalJson.of("{\"x\":1e3,\"y\":1.50,\"z\":12345678901234567890}"))
                .isEqualTo("{\"x\":1000,\"y\":1.50,\"z\":12345678901234567890}");
    }

    @Test
    void canonicalTextIsIndependentOfInputFormatting() {
        assertThat(CanonicalJson.of("{\"k\":\"caf\\u00e9\",\"n\":true}"))
                .isEqualTo(CanonicalJson.of("{ \"n\" : true , \"k\" : \"café\" }"));
    }

    @Test
    void canonicalJsonRefusesText() {
        assertThatThrownBy(() -> CanonicalJson.of("not json")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyLinkDependsOnThePreviousHashAndTheEntry() {
        Instant at = Instant.parse("2027-03-11T17:42:00.123456Z");
        UUID subject = UUID.fromString("0197f3a2-5c1e-7b3a-9d10-3c4f1a2b9e01");
        String entry = AuditTrail.canonicalEntry("APPROVAL_DECISION", subject, "STAFF", "s1", "{\"role\":\"MAKER\"}", at);
        byte[] first = AuditHash.next(AuditHash.genesis(), entry);

        assertThat(AuditHash.next(AuditHash.genesis(), entry)).isEqualTo(first);
        assertThat(AuditHash.next(first, entry)).isNotEqualTo(first);
        String edited = AuditTrail.canonicalEntry("APPROVAL_DECISION", subject, "STAFF", "s1", "{\"role\":\"CHECKER\"}", at);
        assertThat(AuditHash.next(AuditHash.genesis(), edited)).isNotEqualTo(first);
        String retimed = AuditTrail.canonicalEntry("APPROVAL_DECISION", subject, "STAFF", "s1", "{\"role\":\"MAKER\"}",
                at.plusNanos(1_000));
        assertThat(AuditHash.next(AuditHash.genesis(), retimed)).isNotEqualTo(first);
    }

    @Test
    void staffRefsAreStableAndDoNotRevealTheId() {
        UUID staff = UUID.randomUUID();
        assertThat(StaffRefs.of(staff)).isEqualTo(StaffRefs.of(staff)).startsWith("staff:").hasSize(22)
                .doesNotContain(staff.toString().substring(0, 8));
        assertThat(StaffRefs.of(UUID.randomUUID())).isNotEqualTo(StaffRefs.of(staff));
    }
}
