package com.paypalseed.payments.application.usecases;

import com.paypalseed.payments.api.dto.PaymentsDtos.OrderPurpose;

/**
 * The request bodies that {@link PayPalCommandExecutor} hashes to tell a replay from a different request under
 * the same key. They are records on purpose: a record serialises its fields in declaration order, so the hash is
 * the same in every JVM run. A {@code Map.of(...)} with several entries iterates in an order that changes from
 * run to run, which made a replay after a restart look like a different request and answer {@code 409}.
 */
final class Fingerprints {

    private Fingerprints() {
    }

    record Order(long amountCents, OrderPurpose purpose) {
    }

    record Refund(String parent, long amountCents) {
    }
}
