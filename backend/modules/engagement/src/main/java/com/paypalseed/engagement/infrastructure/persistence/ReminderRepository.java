package com.paypalseed.engagement.infrastructure.persistence;

import static com.paypalseed.engagement.infrastructure.persistence.jooq.Tables.CONTACT;
import static com.paypalseed.engagement.infrastructure.persistence.jooq.Tables.REMINDER;

import com.paypalseed.engagement.infrastructure.persistence.jooq.tables.records.ReminderRecord;
import com.paypalseed.platform.persistence.PgSql;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;

@Repository
public class ReminderRepository {

    public record Contact(UUID memberId, String phoneE164, String locale, String timezone, boolean optedOut) {
    }

    public record Reminder(String dedupeKey, UUID memberId, UUID loanId, String type, String channel, String locale,
                           String body, int attempts) {
    }

    private final DSLContext dsl;

    public ReminderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Contact> contact(UUID memberId) {
        return dsl.selectFrom(CONTACT)
                .where(CONTACT.MEMBER_ID.eq(memberId))
                .fetchOptional(r -> new Contact(r.get(CONTACT.MEMBER_ID), r.get(CONTACT.PHONE_E164), r.get(CONTACT.LOCALE),
                        r.get(CONTACT.TIMEZONE), r.get(CONTACT.SMS_OPT_OUT_AT) != null));
    }

    public void upsertContact(UUID memberId, String phone, String locale, String timezone) {
        dsl.insertInto(CONTACT)
                .set(CONTACT.MEMBER_ID, memberId)
                .set(CONTACT.PHONE_E164, phone)
                .set(CONTACT.LOCALE, locale)
                .set(CONTACT.TIMEZONE, timezone)
                .onConflict(CONTACT.MEMBER_ID)
                .doUpdate()
                .set(CONTACT.PHONE_E164, DSL.excluded(CONTACT.PHONE_E164))
                .set(CONTACT.LOCALE, DSL.excluded(CONTACT.LOCALE))
                .set(CONTACT.TIMEZONE, DSL.excluded(CONTACT.TIMEZONE))
                .set(CONTACT.UPDATED_AT, PgSql.now())
                .execute();
    }

    public Optional<UUID> memberByPhone(String phone) {
        return dsl.select(CONTACT.MEMBER_ID)
                .from(CONTACT)
                .where(CONTACT.PHONE_E164.eq(phone))
                .fetchOptional(CONTACT.MEMBER_ID);
    }

    public void setOptOut(UUID memberId, boolean optOut) {
        Field<Instant> optOutAt = optOut ? PgSql.now() : DSL.castNull(SQLDataType.INSTANT);
        dsl.update(CONTACT)
                .set(CONTACT.SMS_OPT_OUT_AT, optOutAt)
                .set(CONTACT.UPDATED_AT, PgSql.now())
                .where(CONTACT.MEMBER_ID.eq(memberId))
                .execute();
    }

    /** Returns false when this message was already decided (dedupe on the business key). */
    public boolean enqueue(String key, UUID memberId, UUID loanId, String type, String channel, String locale, String body,
                           Instant sendAfter, String status) {
        return dsl.insertInto(REMINDER)
                .set(REMINDER.DEDUPE_KEY, key)
                .set(REMINDER.MEMBER_ID, memberId)
                .set(REMINDER.LOAN_ID, loanId)
                .set(REMINDER.TYPE, type)
                .set(REMINDER.CHANNEL, channel)
                .set(REMINDER.LOCALE, locale)
                .set(REMINDER.BODY, body)
                .set(REMINDER.SEND_AFTER, sendAfter)
                .set(REMINDER.STATUS, status)
                .onConflict(REMINDER.DEDUPE_KEY)
                .doNothing()
                .execute() == 1;
    }

    public List<Reminder> claimDue(Instant now, int limit) {
        return dsl.selectFrom(REMINDER)
                .where(REMINDER.STATUS.eq("QUEUED"))
                .and(REMINDER.SEND_AFTER.le(now))
                .orderBy(REMINDER.SEND_AFTER)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch(ReminderRepository::map);
    }

    public void mark(String key, String status, String error) {
        Field<Instant> sentAt = "SENT".equals(status) ? PgSql.now() : REMINDER.SENT_AT;
        dsl.update(REMINDER)
                .set(REMINDER.STATUS, status)
                .set(REMINDER.ATTEMPTS, REMINDER.ATTEMPTS.plus(1))
                .set(REMINDER.LAST_ERROR, error)
                .set(REMINDER.SENT_AT, sentAt)
                .where(REMINDER.DEDUPE_KEY.eq(key))
                .execute();
    }

    public void retryLater(String key, Instant at, String error) {
        dsl.update(REMINDER)
                .set(REMINDER.ATTEMPTS, REMINDER.ATTEMPTS.plus(1))
                .set(REMINDER.SEND_AFTER, at)
                .set(REMINDER.LAST_ERROR, error)
                .where(REMINDER.DEDUPE_KEY.eq(key))
                .execute();
    }

    /** The loan of the member's newest message, so an SMS reply can be tied to a loan. */
    public Optional<UUID> latestLoanOf(UUID memberId) {
        return dsl.select(REMINDER.LOAN_ID)
                .from(REMINDER)
                .where(REMINDER.MEMBER_ID.eq(memberId))
                .orderBy(REMINDER.CREATED_AT.desc())
                .limit(1)
                .fetchOptional(REMINDER.LOAN_ID);
    }

    public List<Reminder> forLoan(UUID loanId) {
        return dsl.selectFrom(REMINDER)
                .where(REMINDER.LOAN_ID.eq(loanId))
                .orderBy(REMINDER.CREATED_AT)
                .fetch(ReminderRepository::map);
    }

    private static Reminder map(ReminderRecord r) {
        return new Reminder(r.get(REMINDER.DEDUPE_KEY), r.get(REMINDER.MEMBER_ID), r.get(REMINDER.LOAN_ID),
                r.get(REMINDER.TYPE), r.get(REMINDER.CHANNEL), r.get(REMINDER.LOCALE), r.get(REMINDER.BODY),
                r.get(REMINDER.ATTEMPTS));
    }
}
