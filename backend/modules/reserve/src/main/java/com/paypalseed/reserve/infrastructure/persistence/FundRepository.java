package com.paypalseed.reserve.infrastructure.persistence;

import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.COVERED_MEMBER;
import static com.paypalseed.reserve.infrastructure.persistence.jooq.Tables.FUND;

import com.paypalseed.platform.persistence.PgSql;
import com.paypalseed.reserve.domain.model.FundState;
import java.time.LocalDate;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The single fund row and the set of members it has helped. */
@Repository
public class FundRepository {

    private static final int FUND_ID = 1;

    private final DSLContext dsl;

    FundRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Reads the fund and holds its row until the transaction ends: everything that moves fund money starts here. */
    public FundState lock() {
        return dsl.selectFrom(FUND).where(FUND.ID.eq(FUND_ID)).forUpdate()
                .fetchOne(r -> new FundState(r.getBalanceCents(), r.getTotalDonatedCents(), r.getTotalPaidOutCents(),
                        r.getDonationCount(), r.getMembersCovered()));
    }

    public FundState read() {
        return dsl.selectFrom(FUND).where(FUND.ID.eq(FUND_ID))
                .fetchOne(r -> new FundState(r.getBalanceCents(), r.getTotalDonatedCents(), r.getTotalPaidOutCents(),
                        r.getDonationCount(), r.getMembersCovered()));
    }

    public void save(FundState state) {
        dsl.update(FUND)
                .set(FUND.BALANCE_CENTS, state.balanceCents())
                .set(FUND.TOTAL_DONATED_CENTS, state.totalDonatedCents())
                .set(FUND.TOTAL_PAID_OUT_CENTS, state.totalPaidOutCents())
                .set(FUND.DONATION_COUNT, state.donationCount())
                .set(FUND.MEMBERS_COVERED, state.membersCovered())
                .set(FUND.UPDATED_AT, PgSql.now())
                .where(FUND.ID.eq(FUND_ID))
                .execute();
    }

    /** True the first time this member is covered. */
    public boolean markCovered(UUID memberId, LocalDate on) {
        return dsl.insertInto(COVERED_MEMBER)
                .set(COVERED_MEMBER.MEMBER_ID, memberId)
                .set(COVERED_MEMBER.FIRST_COVERED_ON, on)
                .onConflict(COVERED_MEMBER.MEMBER_ID).doNothing()
                .execute() == 1;
    }
}
