package com.paypalseed.fraud.infrastructure.persistence;

import static com.paypalseed.fraud.infrastructure.persistence.jooq.Tables.PAYOUT_RECIPIENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.fraud.infrastructure.persistence.DestinationRepository.Change;
import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.persistence.ModuleMigrations;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The recipient of the latest payout, stored as a hash only (ADR 0027). */
class RecipientRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static DSLContext dsl;
    private static RecipientRepository recipients;

    @BeforeAll
    static void migrate() {
        DataSource ds = new DriverManagerDataSource(EmbeddedPostgresSupport.database("fraud_recipient"));
        new ModuleMigrations(ds).migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        recipients = new RecipientRepository(dsl);
    }

    @Test
    void theFirstRecipientIsABaselineThenSameOrChanged() {
        UUID member = Ids.newId();

        assertThat(recipients.record(member, HASH_A, T0)).as("the first one seen is the baseline").isEqualTo(Change.FIRST);
        assertThat(recipients.record(member, HASH_A, T0.plusSeconds(60))).isEqualTo(Change.SAME);
        assertThat(recipients.record(member, HASH_B, T0.plusSeconds(120))).isEqualTo(Change.CHANGED);
        assertThat(recipients.record(member, HASH_B, T0.plusSeconds(180))).isEqualTo(Change.SAME);
        assertThat(recipients.record(member, HASH_A, T0.plusSeconds(240))).as("paying the old recipient again is a change").isEqualTo(Change.CHANGED);

        assertThat(dsl.fetchCount(PAYOUT_RECIPIENT, PAYOUT_RECIPIENT.MEMBER_ID.eq(member))).isEqualTo(1);
        assertThat(recipients.record(Ids.newId(), HASH_A, T0)).as("another member has a recipient of their own").isEqualTo(Change.FIRST);
    }

    @Test
    void aRecipientMustBeAHashNeverAnIdentifier() {
        assertThatThrownBy(() -> recipients.record(Ids.newId(), "someone@example.test", T0)).isInstanceOf(DataAccessException.class);
    }
}
