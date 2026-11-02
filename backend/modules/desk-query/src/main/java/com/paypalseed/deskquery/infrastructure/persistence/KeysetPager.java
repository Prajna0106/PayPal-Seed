package com.paypalseed.deskquery.infrastructure.persistence;

import com.paypalseed.deskquery.api.dto.DeskQueryDtos.Total;
import com.paypalseed.deskquery.domain.model.ColumnType;
import com.paypalseed.deskquery.domain.model.GridQuery;
import com.paypalseed.deskquery.domain.model.SortKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSON;
import org.jooq.OrderField;
import org.jooq.Param;
import org.jooq.QueryPart;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

/**
 * Keyset (seek) pagination over a validated {@link GridQuery}. Position comes from the last row's sort values, never from
 * an offset, so a page costs the same at row one million as at row one, and rows inserted while an officer scrolls
 * cannot shift the next page. Every sort key is followed by the row id so ties have one total order.
 *
 * <p>A request only ever names a column; the expression behind the name is fixed in the repository's column map. Values
 * are bound parameters. Nothing from the request becomes SQL text.
 */
final class KeysetPager {

    /** Planner statements start with the top node, whose "Plan Rows" is the estimate for the whole query. */
    private static final Pattern PLAN_ROWS = Pattern.compile("\"Plan Rows\":\\s*(\\d+)");

    private KeysetPager() {
    }

    /**
     * A sortable expression and its type. The expression must never be null: nullable columns are coalesced to a sentinel
     * so a cursor can always carry the value and the comparison is always defined.
     */
    record Column(Field<?> expression, ColumnType type) {
    }

    static <R> Keyset<R> fetch(DSLContext dsl, Table<?> table, Condition where, List<? extends SelectFieldOrAsterisk> projection,
                               Map<String, Column> columns, Field<UUID> id, GridQuery query, Function<Record, R> mapper) {
        List<SortKey> sort = query.sort();
        List<Field<?>> keys = new ArrayList<>();
        List<Boolean> descending = new ArrayList<>();
        for (SortKey k : sort) {
            keys.add(columns.get(k.column()).expression());
            descending.add(k.descending());
        }
        keys.add(id);
        // The id follows the last sort key's direction, so a single-column sort is one index scan in one direction.
        descending.add(sort.get(sort.size() - 1).descending());

        Condition condition = where;
        if (query.after() != null) {
            List<Object> after = new ArrayList<>();
            for (int i = 0; i < sort.size(); i++) {
                after.add(columns.get(sort.get(i).column()).type().parse(query.after().get(i)));
            }
            after.add(UUID.fromString(query.after().get(sort.size())));
            condition = condition.and(seek(keys, descending, after));
        }

        List<SelectFieldOrAsterisk> select = new ArrayList<>(projection);
        List<OrderField<?>> order = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            select.add(keys.get(i).as("k" + i));
            order.add(keys.get(i).sort(descending.get(i) ? SortOrder.DESC : SortOrder.ASC));
        }
        Result<Record> result = dsl.select(select).from(table).where(condition).orderBy(order).limit(query.limit() + 1).fetch();

        boolean hasMore = result.size() > query.limit();
        List<R> rows = result.stream().limit(query.limit()).map(mapper).toList();
        List<String> lastKeys = null;
        if (hasMore) {
            Record last = result.get(query.limit() - 1);
            lastKeys = new ArrayList<>();
            for (int i = 0; i < sort.size(); i++) {
                lastKeys.add(columns.get(sort.get(i).column()).type().format(last.get("k" + i)));
            }
            lastKeys.add(ColumnType.ID.format(last.get("k" + sort.size())));
        }
        return new Keyset<>(rows, lastKeys);
    }

    /**
     * Rows strictly after the cursor in the requested order. When every key runs the same way this is one row-value
     * comparison, which PostgreSQL answers from a composite index; mixed directions expand into an OR of prefixes.
     */
    static Condition seek(List<Field<?>> keys, List<Boolean> descending, List<Object> values) {
        int n = keys.size();
        boolean uniform = descending.stream().distinct().count() == 1;
        if (uniform) {
            StringBuilder sql = new StringBuilder("(");
            StringBuilder bound = new StringBuilder("(");
            QueryPart[] parts = new QueryPart[2 * n];
            for (int i = 0; i < n; i++) {
                sql.append(i == 0 ? "" : ", ").append('{').append(i).append('}');
                bound.append(i == 0 ? "" : ", ").append('{').append(n + i).append('}');
                parts[i] = keys.get(i);
                parts[n + i] = bind(keys.get(i), values.get(i));
            }
            return DSL.condition(sql + (descending.get(0) ? ") < " : ") > ") + bound + ")", parts);
        }
        Condition any = null;
        Condition prefixEqual = null;
        for (int i = 0; i < n; i++) {
            Condition after = descending.get(i) ? lessThan(keys.get(i), values.get(i)) : greaterThan(keys.get(i), values.get(i));
            Condition step = prefixEqual == null ? after : prefixEqual.and(after);
            any = any == null ? step : any.or(step);
            Condition same = equal(keys.get(i), values.get(i));
            prefixEqual = prefixEqual == null ? same : prefixEqual.and(same);
        }
        return any;
    }

    /**
     * A count that stays cheap on a large table: exact up to {@code cap} rows, otherwise the planner's estimate, flagged as
     * such. The bounded count reads at most {@code cap + 1} index entries.
     */
    static Total total(DSLContext dsl, Table<?> table, Condition where, int cap) {
        int counted = dsl.fetchCount(dsl.selectOne().from(table).where(where).limit(cap + 1));
        if (counted <= cap) {
            return new Total(counted, true);
        }
        String sql = dsl.selectOne().from(table).where(where).getSQL(ParamType.INLINED);
        Object plan = dsl.fetchValue("explain (format json) " + sql);
        Matcher m = PLAN_ROWS.matcher(plan instanceof JSON json ? json.data() : String.valueOf(plan));
        return new Total(m.find() ? Math.max(cap + 1, Long.parseLong(m.group(1))) : cap + 1, false);
    }

    private static <T> Param<T> bind(Field<T> field, Object value) {
        return DSL.val(value, field);
    }

    private static <T> Condition greaterThan(Field<T> field, Object value) {
        return field.gt(bind(field, value));
    }

    private static <T> Condition lessThan(Field<T> field, Object value) {
        return field.lt(bind(field, value));
    }

    private static <T> Condition equal(Field<T> field, Object value) {
        return field.eq(bind(field, value));
    }
}
