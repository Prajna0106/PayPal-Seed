package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.COVER_REQUEST;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.DONATION;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.DONATION_INTENT;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.FUND;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.LOAN_EXPOSURE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paypalseed.platform.kernel.Ids;
import com.paypalseed.platform.persistence.ModuleMigrations;
import com.paypalseed.platform.testing.EmbeddedPostgresSupport;
import com.paypalseed.reserve.api.dto.ReserveDtos.CoverStatus;
import com.paypalseed.reserve.domain.model.FundState;
import com.paypalseed.reserve.infrastructure.persistence.CoverRepository.CoverRow;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository.DonationRow;
import com.paypalseed.reserve.infrastructure.persistence.DonationRepository.IntentRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The reserve schema against a real PostgreSQL 16: constraints, triggers and the queries the use cases depend on. */
class ReserveRepositoryTest {

    private static DSLContext dsl;
    private static FundRepository funds;
    private static DonationRepository donations;
    private static CoverRepository covers;
    private static ExposureRepository exposure;

    @BeforeAll
    static void migrate() {
        DataSource ds = new DriverManagerDataSource(EmbeddedPostgresSupport.database("reserve_repository"));
        new ModuleMigrations(ds).migrate();
        dsl = DSL.using(ds, SQLDialect.POSTGRES);
        funds = new FundRepository(dsl);
        donations = new DonationRepository(dsl);
        covers = new CoverRepository(dsl);
        exposure = new ExposureRepository(dsl);
    }

    @BeforeEach
    void resetFund() {
        funds.save(FundState.EMPTY);
    }

    @Test
    void theFundStartsEmptyAndKeepsWhatIsSaved() {
        assertThat(funds.read()).isEqualTo(FundState.EMPTY);
        FundState saved = FundState.EMPTY.donate(12_345).payOut(345, true);
        funds.save(saved);
        assertThat(funds.lock()).isEqualTo(saved);
    }

    @Test
    void theDatabaseRefusesAnImpossibleFund() {
        assertThatThrownBy(() -> dsl.update(FUND).set(FUND.BALANCE_CENTS, -1L).execute()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> dsl.update(FUND).set(FUND.BALANCE_CENTS, 5L).execute())
                .as("balance must equal donated minus paid out").isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> dsl.insertInto(FUND).set(FUND.ID, 2).set(FUND.BALANCE_CENTS, 0L)
                .set(FUND.TOTAL_DONATED_CENTS, 0L).set(FUND.TOTAL_PAID_OUT_CENTS, 0L).set(FUND.DONATION_COUNT, 0)
                .set(FUND.MEMBERS_COVERED, 0).execute()).as("only one fund row").isInstanceOf(DataAccessException.class);
    }

    @Test
    void aMemberIsCountedAsCoveredOnlyOnce() {
        UUID member = Ids.newId();
        assertThat(funds.markCovered(member, LocalDate.of(2026, 11, 10))).isTrue();
        assertThat(funds.markCovered(member, LocalDate.of(2026, 12, 1))).isFalse();
    }

    @Test
    void aCaptureIsRecordedOnceAndNeverEdited() {
        String capture = "CAP-" + Ids.newId();
        DonationRow row = donation(capture, 5_000, false, "Maria");
        donations.insert(row);
        assertThat(donations.byCapture(capture)).contains(row);
        assertThatThrownBy(() -> donations.insert(donation(capture, 5_000, true, null))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> dsl.update(DONATION).set(DONATION.AMOUNT_CENTS, 1L).where(DONATION.CAPTURE_ID.eq(capture)).execute())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.deleteFrom(DONATION).where(DONATION.CAPTURE_ID.eq(capture)).execute())
                .hasMessageContaining("append-only");
    }

    @Test
    void aNameCanOnlyBeStoredForADonorWhoChoseToBeShown() {
        assertThatThrownBy(() -> donations.insert(donation("CAP-" + Ids.newId(), 100, true, "Leaky Name")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> donations.insert(donation("CAP-" + Ids.newId(), 100, false, null)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> donations.insertIntent(new IntentRow(Ids.newId(), "k-" + Ids.newId(), "h", true, "Leaky Name")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void feeMustBeLessThanTheGift() {
        assertThatThrownBy(() -> donations.insert(new DonationRow(Ids.newId(), "CAP-" + Ids.newId(), 100, 100, true, null,
                Ids.newId(), LocalDate.of(2026, 11, 10)))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void onlyOptedInNamesAreListedNewestFirst() throws InterruptedException {
        donations.insert(donation("CAP-a-" + Ids.newId(), 100, false, "Oldest Supporter"));
        Thread.sleep(5);
        donations.insert(donation("CAP-b-" + Ids.newId(), 100, true, null));
        Thread.sleep(5);
        donations.insert(donation("CAP-c-" + Ids.newId(), 100, false, "Newest Supporter"));
        assertThat(donations.recentSupporters(1000)).doesNotContainNull().containsSubsequence("Newest Supporter", "Oldest Supporter");
        assertThat(donations.recentSupporters(1)).containsExactly("Newest Supporter");
    }

    @Test
    void anIntentKeyIsStoredOnce() {
        String key = "k-" + Ids.newId();
        IntentRow first = new IntentRow(Ids.newId(), key, "hash-1", false, "Tom");
        assertThat(donations.insertIntent(first)).isTrue();
        assertThat(donations.insertIntent(new IntentRow(Ids.newId(), key, "hash-2", true, null))).isFalse();
        assertThat(donations.intentByKey(key)).contains(first);
        assertThat(donations.intent(first.id())).contains(first);
        assertThat(donations.intent(Ids.newId())).isEmpty();
        assertThatThrownBy(() -> dsl.update(DONATION_INTENT).set(DONATION_INTENT.ANONYMOUS, true)
                .where(DONATION_INTENT.ID.eq(first.id())).execute())
                .hasMessageContaining("append-only");
    }

    @Test
    void oneCoverRequestMayBeOpenPerLoan() {
        UUID loan = Ids.newId();
        covers.insert(cover(Ids.newId(), loan, 1_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 11, 10)));
        assertThat(covers.hasOpenFor(loan)).isTrue();
        assertThatThrownBy(() -> covers.insert(cover(Ids.newId(), loan, 2_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 11, 10))))
                .isInstanceOf(DataAccessException.class);
        assertThat(covers.hasOpenFor(Ids.newId())).isFalse();
    }

    @Test
    void approvingAndClosingAffectOnlyPendingRequests() {
        CoverRow pending = cover(Ids.newId(), Ids.newId(), 1_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 11, 10));
        covers.insert(pending);
        UUID entry = Ids.newId();
        covers.markApproved(pending.id(), entry);
        CoverRow approved = covers.find(pending.id()).orElseThrow();
        assertThat(approved.status()).isEqualTo(CoverStatus.APPROVED);
        assertThat(approved.journalEntryId()).isEqualTo(entry);
        assertThat(covers.hasOpenFor(pending.loanId())).isFalse();

        covers.markClosed(pending.id(), CoverStatus.REJECTED, "LATE");
        assertThat(covers.find(pending.id()).orElseThrow().status()).as("an approved request cannot be rejected later")
                .isEqualTo(CoverStatus.APPROVED);

        CoverRow other = cover(Ids.newId(), Ids.newId(), 500, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 11, 10));
        covers.insert(other);
        covers.markClosed(other.id(), CoverStatus.FAILED, "RESERVE_INSUFFICIENT_FUNDS");
        assertThat(covers.find(other.id()).orElseThrow()).extracting(CoverRow::status, CoverRow::closedReason)
                .containsExactly(CoverStatus.FAILED, "RESERVE_INSUFFICIENT_FUNDS");
        assertThatThrownBy(() -> dsl.deleteFrom(COVER_REQUEST).where(COVER_REQUEST.ID.eq(other.id())).execute())
                .hasMessageContaining("append-only");
    }

    @Test
    void theYearlyCommitmentCountsPendingAndApprovedInThatYearOnly() {
        UUID member = Ids.newId();
        covers.insert(cover(member, Ids.newId(), 1_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 3, 1)));
        CoverRow approved = cover(member, Ids.newId(), 2_500, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 12, 31));
        covers.insert(approved);
        covers.markApproved(approved.id(), Ids.newId());
        CoverRow rejected = cover(member, Ids.newId(), 9_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 5, 1));
        covers.insert(rejected);
        covers.markClosed(rejected.id(), CoverStatus.REJECTED, "NO");
        covers.insert(cover(member, Ids.newId(), 7_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2025, 12, 31)));
        covers.insert(cover(Ids.newId(), Ids.newId(), 4_000, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 3, 1)));

        assertThat(covers.committed(member, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))).isEqualTo(3_500);
        assertThat(covers.committed(member, LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1))).isZero();
    }

    @Test
    void pagesAreNewestFirstWithAKeysetCursorAndAStatusFilter() throws InterruptedException {
        UUID member = Ids.newId();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CoverRow row = cover(member, Ids.newId(), 100 + i, CoverStatus.PENDING_APPROVAL, LocalDate.of(2026, 11, 10));
            covers.insert(row);
            ids.add(row.id());
            Thread.sleep(3);
        }
        covers.markClosed(ids.get(1), CoverStatus.REJECTED, "NO");

        List<CoverRow> all = covers.page(List.of(), null, 500).stream().filter(r -> r.memberId().equals(member)).toList();
        assertThat(all).extracting(CoverRow::id).containsExactly(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0));

        List<CoverRow> older = covers.page(List.of(), ids.get(3), 2);
        assertThat(older).extracting(CoverRow::id).containsExactly(ids.get(2), ids.get(1));

        assertThat(covers.page(List.of(CoverStatus.REJECTED), null, 500)).extracting(CoverRow::id).contains(ids.get(1))
                .doesNotContain(ids.get(0));
    }

    @Test
    void exposureFollowsTheNewestEventAndIgnoresOlderOnes() throws InterruptedException {
        dsl.deleteFrom(LOAN_EXPOSURE).execute();
        UUID loanA = Ids.newId();
        UUID loanB = Ids.newId();
        UUID older = Ids.newId();
        Thread.sleep(3);
        UUID newer = Ids.newId();

        exposure.set(loanA, 10_000, newer);
        exposure.set(loanA, 99_999, older);
        assertThat(exposure.total()).as("an older event must not overwrite a newer one").isEqualTo(10_000);

        exposure.set(loanA, 10_000, newer);
        exposure.set(loanB, 2_500, older);
        assertThat(exposure.total()).isEqualTo(12_500);

        Thread.sleep(3);
        exposure.set(loanA, 0, Ids.newId());
        assertThat(exposure.total()).isEqualTo(2_500);
    }

    private static DonationRow donation(String capture, long amount, boolean anonymous, String name) {
        return new DonationRow(Ids.newId(), capture, amount, 0, anonymous, name, Ids.newId(), LocalDate.of(2026, 11, 10));
    }

    private static CoverRow cover(UUID member, UUID loan, long amount, CoverStatus status, LocalDate on) {
        return new CoverRow(Ids.newId(), member, loan, amount, "INCOME_DROP_VERIFIED", null, null, status, null, Ids.newId(), on,
                "key-" + Ids.newId(), "hash", null, null);
    }
}
