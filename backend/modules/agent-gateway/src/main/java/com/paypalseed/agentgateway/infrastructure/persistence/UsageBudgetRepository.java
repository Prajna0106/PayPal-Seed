package com.paypalseed.agentgateway.infrastructure.persistence;

import static com.paypalseed.agentgateway.infrastructure.persistence.jooq.Tables.USAGE_BUDGET;

import java.time.LocalDate;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class UsageBudgetRepository {

    private final DSLContext dsl;

    public UsageBudgetRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long costMicroUsd(LocalDate day) {
        return dsl.select(USAGE_BUDGET.COST_MICRO_USD).from(USAGE_BUDGET).where(USAGE_BUDGET.DAY.eq(day))
                .fetchOptional(USAGE_BUDGET.COST_MICRO_USD).orElse(0L);
    }

    /** Atomic add, so concurrent calls never lose a cent. Returns the day's new total cost. */
    public long add(LocalDate day, long tokens, long costMicroUsd) {
        return dsl.insertInto(USAGE_BUDGET)
                .set(USAGE_BUDGET.DAY, day)
                .set(USAGE_BUDGET.CALLS, 1)
                .set(USAGE_BUDGET.TOKENS_USED, tokens)
                .set(USAGE_BUDGET.COST_MICRO_USD, costMicroUsd)
                .onConflict(USAGE_BUDGET.DAY)
                .doUpdate()
                .set(USAGE_BUDGET.CALLS, USAGE_BUDGET.CALLS.plus(1))
                .set(USAGE_BUDGET.TOKENS_USED, USAGE_BUDGET.TOKENS_USED.plus(tokens))
                .set(USAGE_BUDGET.COST_MICRO_USD, USAGE_BUDGET.COST_MICRO_USD.plus(costMicroUsd))
                .returning(USAGE_BUDGET.COST_MICRO_USD)
                .fetchSingle()
                .getCostMicroUsd();
    }
}
