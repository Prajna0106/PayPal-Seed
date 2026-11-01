package com.paypalseed.deskquery.infrastructure.persistence;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.BucketTotal;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.OutcomeCount;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.StatusTotal;
import com.paypalseed.deskquery.api.dto.DeskQueryDtos.WorkloadItem;
import com.paypalseed.deskquery.domain.model.ImpactFigures;
import com.paypalseed.deskquery.domain.model.OnTimeRate;
import com.paypalseed.deskquery.domain.model.SlaBucket;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

/**
 * Aggregate reads over the projections: portfolio totals, the inclusion figures and the workload cells. The statements are
 * fixed text over the module's own tables; nothing a caller sends is part of them.
 */
@Repository
public class SummaryRepository {

    /** Loans the desk counts: schedule seen, and not returned in the cooling-off window. */
    private static final String REAL_LOANS = "deskquery.loan_view where principal_cents is not null and status <> 'CANCELLED'";

    private final DSLContext dsl;

    public SummaryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<StatusTotal> byStatus() {
        return dsl.fetch("select status, count(*), coalesce(sum(outstanding_cents), 0) from deskquery.loan_view "
                        + "where principal_cents is not null group by status order by status")
                .map(r -> new StatusTotal(r.get(0, String.class), r.get(1, Long.class), r.get(2, Long.class)));
    }

    /** Aging applies to loans still owed; a repaid loan has no bucket worth showing. */
    public List<BucketTotal> byAgingBucket() {
        return dsl.fetch("select aging_bucket, count(*), coalesce(sum(outstanding_cents), 0) from deskquery.loan_view "
                        + "where principal_cents is not null and status = 'ACTIVE' group by aging_bucket")
                .map(r -> new BucketTotal(r.get(0, String.class), r.get(1, Long.class), r.get(2, Long.class)));
    }

    /** Instalments that fell due on or before {@code today} against those recorded as missed. */
    public OnTimeRate onTime(LocalDate today) {
        Record due = dsl.fetchOne("select count(*) from deskquery.loan_instalment_view i "
                + "join deskquery.loan_view l using (loan_id) "
                + "where l.principal_cents is not null and l.status <> 'CANCELLED' and i.due_date <= {0}",
                DSL.val(today, SQLDataType.LOCALDATE));
        Record missed = dsl.fetchOne("select coalesce(sum(missed_instalments), 0) from " + REAL_LOANS);
        return OnTimeRate.of(due.get(0, Long.class), missed.get(0, Long.class));
    }

    public long openHardshipCases() {
        return dsl.fetchOne("select count(*) from deskquery.hardship_case_view where state = 'OPEN'").get(0, Long.class);
    }

    public List<OutcomeCount> hardshipOutcomes() {
        return dsl.fetch("select coalesce(closed_reason, 'UNSPECIFIED'), count(*) from deskquery.hardship_case_view "
                        + "where state = 'CLOSED' group by 1 order by 2 desc, 1")
                .map(r -> new OutcomeCount(r.get(0, String.class), r.get(1, Long.class)));
    }

    /** The raw counts behind the inclusion figures; the first loan of a member is the one disbursed earliest. */
    public ImpactFigures.Raw impact() {
        Record r = dsl.fetchOne("""
                select
                  (select count(*) from (select member_ref from deskquery.member_savings
                                          union
                                         select member_ref from deskquery.loan_view
                                          where principal_cents is not null and status <> 'CANCELLED') m),
                  (select count(distinct member_ref) from %1$s),
                  (select count(*) from %1$s),
                  (select coalesce(sum(principal_cents), 0) from %1$s),
                  (select count(*) from (select distinct on (member_ref) status, repaid_on_plan from %1$s
                                          order by member_ref, disbursed_on, loan_id) f where f.status = 'REPAID'),
                  (select count(*) from (select distinct on (member_ref) status, repaid_on_plan from %1$s
                                          order by member_ref, disbursed_on, loan_id) f
                    where f.status = 'REPAID' and f.repaid_on_plan)
                """.formatted(REAL_LOANS));
        return new ImpactFigures.Raw(r.get(0, Long.class), r.get(1, Long.class), r.get(2, Long.class), r.get(3, Long.class),
                r.get(4, Long.class), r.get(5, Long.class));
    }

    /**
     * Items waiting for an officer or a checker, by SLA bucket. A loan application waits for the OFFICER until its first
     * approval and for the CHECKER after it; a governance request does the same by stage. The bucket boundaries are those
     * of {@link SlaBucket#of}.
     */
    public List<WorkloadItem> workload(Instant now, Duration dueSoon) {
        String bucket = "case when sla_due_at is null then '" + SlaBucket.NO_SLA + "' "
                + "when sla_due_at <= {0} then '" + SlaBucket.OVERDUE + "' "
                + "when sla_due_at <= {1} then '" + SlaBucket.DUE_SOON + "' else '" + SlaBucket.ON_TRACK + "' end";
        String sql = "select 'LOAN_APPLICATION' as kind, case status when 'SUBMITTED' then 'OFFICER' else 'CHECKER' end as role, "
                + bucket + " as bucket, count(*) as waiting, min(sla_due_at) as earliest "
                + "from deskquery.application_view where status in ('SUBMITTED', 'PENDING_SECOND_APPROVAL') group by 1, 2, 3 "
                + "union all "
                + "select 'HARDSHIP_DECISION', case stage when 0 then 'OFFICER' else 'CHECKER' end, " + bucket
                + ", count(*), min(sla_due_at) from deskquery.approval_view where state = 'OPEN' group by 1, 2, 3 "
                + "order by 1, 2, 3";
        return dsl.fetch(sql, DSL.val(now, SQLDataType.INSTANT), DSL.val(now.plus(dueSoon), SQLDataType.INSTANT))
                .map(r -> new WorkloadItem(r.get(0, String.class), r.get(1, String.class), r.get(2, String.class),
                        r.get(3, Long.class), instant(r.get(4))));
    }

    /** The newest change in any projection: the summary is as of this instant. */
    public Optional<Instant> asOf() {
        Record r = dsl.fetchOne("select max(m) from (select max(updated_at) m from deskquery.loan_view "
                + "union all select max(updated_at) from deskquery.hardship_case_view "
                + "union all select max(updated_at) from deskquery.member_savings "
                + "union all select max(updated_at) from deskquery.approval_view "
                + "union all select max(updated_at) from deskquery.application_view "
                + "union all select max(updated_at) from deskquery.assessment_view) t");
        return Optional.ofNullable(instant(r.get(0)));
    }

    /** Plain SQL hands back the driver's date-time type; the API speaks {@link Instant}. */
    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant i) {
            return i;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }
}
