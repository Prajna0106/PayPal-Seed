package com.paypalseed.payments.infrastructure.persistence;

import static com.paypalseed.payments.infrastructure.persistence.jooq.Tables.PAYPAL_CATALOG_ENTRY;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** {@code payments.paypal_catalog_entry}: the PayPal product and plan ids Seed found or created. */
@Repository
public class CatalogRepository {

    public static final String PRODUCT = "PRODUCT";
    public static final String PLAN = "PLAN";

    private final DSLContext dsl;

    public CatalogRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<String> find(String kind, String name) {
        return dsl.select(PAYPAL_CATALOG_ENTRY.PAYPAL_ID).from(PAYPAL_CATALOG_ENTRY)
                .where(PAYPAL_CATALOG_ENTRY.KIND.eq(kind)).and(PAYPAL_CATALOG_ENTRY.NAME.eq(name))
                .fetchOptional(PAYPAL_CATALOG_ENTRY.PAYPAL_ID);
    }

    /** Keeps the first id recorded for a name; a concurrent bootstrap that found the same object is a no-op. */
    public void remember(String kind, String name, String paypalId) {
        dsl.insertInto(PAYPAL_CATALOG_ENTRY)
                .set(PAYPAL_CATALOG_ENTRY.KIND, kind)
                .set(PAYPAL_CATALOG_ENTRY.NAME, name)
                .set(PAYPAL_CATALOG_ENTRY.PAYPAL_ID, paypalId)
                .onConflict(PAYPAL_CATALOG_ENTRY.KIND, PAYPAL_CATALOG_ENTRY.NAME).doNothing()
                .execute();
    }
}
