package com.paypalseed.engines.schedule;

import com.paypalseed.engines.schedule.MoneyEvent.OverpaymentRefund;
import com.paypalseed.engines.schedule.MoneyEvent.Receipt;
import com.paypalseed.engines.schedule.MoneyEvent.Reversal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Shared test data. Ana's loan: $1,200 over 8 months, due on the 9th, first due 2026-12-09. */
final class Fixtures {

    static final RepaymentPolicy POLICY = RepaymentPolicy.V1;
    static final LocalDate DISBURSED = LocalDate.of(2026, 11, 10);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private Fixtures() {
    }

    static Schedule ana() {
        return ScheduleGenerator.generate(120_000, 8, 9, DISBURSED, POLICY);
    }

    /** Ids increase with creation order, like the UUIDv7 ids production uses. */
    static UUID nextId() {
        return new UUID(0, SEQUENCE.incrementAndGet());
    }

    static Receipt receipt(String date, long dollars) {
        return new Receipt(nextId(), LocalDate.parse(date), dollars * 100);
    }

    static Reversal reversal(Receipt of, String date, long dollars) {
        return new Reversal(nextId(), of.id(), LocalDate.parse(date), dollars * 100);
    }

    static OverpaymentRefund refund(String date, long dollars) {
        return new OverpaymentRefund(nextId(), LocalDate.parse(date), dollars * 100);
    }

    static LocalDate d(String date) {
        return LocalDate.parse(date);
    }
}
