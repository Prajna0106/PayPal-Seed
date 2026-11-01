package com.paypalseed.identity.infrastructure.persistence;

import static com.paypalseed.identity.infrastructure.persistence.jooq.Tables.MEMBER;

import com.paypalseed.identity.api.dto.Language;
import com.paypalseed.identity.api.dto.WalletPreference;
import com.paypalseed.identity.domain.model.Member;
import com.paypalseed.identity.infrastructure.persistence.jooq.tables.records.MemberRecord;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class MemberRepository {

    private final DSLContext dsl;

    MemberRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** False when the payer id already has a member (a concurrent first login won the race). */
    public boolean insertIfAbsent(Member m) {
        return dsl.insertInto(MEMBER)
                .set(MEMBER.ID, m.id())
                .set(MEMBER.PAYPAL_PAYER_ID, m.paypalPayerId())
                .set(MEMBER.EMAIL, m.email())
                .set(MEMBER.DISPLAY_NAME, m.displayName())
                .set(MEMBER.LANGUAGE, m.language().code())
                .set(MEMBER.WALLET_PREFERENCE, m.walletPreference().name())
                .set(MEMBER.PAYPAL_VERIFIED, m.paypalVerified())
                .set(MEMBER.CREATED_AT, m.createdAt())
                .set(MEMBER.LAST_LOGIN_AT, m.lastLoginAt())
                .set(MEMBER.UPDATED_AT, m.updatedAt())
                .onConflictDoNothing()
                .execute() == 1;
    }

    public Optional<Member> find(UUID id) {
        return dsl.selectFrom(MEMBER).where(MEMBER.ID.eq(id)).fetchOptional(MemberRepository::map);
    }

    /** Row lock: logins and profile edits of one member are serialised on it. */
    public Optional<Member> lock(UUID id) {
        return dsl.selectFrom(MEMBER).where(MEMBER.ID.eq(id)).forUpdate().fetchOptional(MemberRepository::map);
    }

    public Optional<Member> lockByPayer(String paypalPayerId) {
        return dsl.selectFrom(MEMBER).where(MEMBER.PAYPAL_PAYER_ID.eq(paypalPayerId)).forUpdate()
                .fetchOptional(MemberRepository::map);
    }

    public Optional<UUID> idForPayer(String paypalPayerId) {
        return dsl.select(MEMBER.ID).from(MEMBER).where(MEMBER.PAYPAL_PAYER_ID.eq(paypalPayerId)).fetchOptional(MEMBER.ID);
    }

    /** Writes every mutable column; the payer id and creation time never change. */
    public void update(Member m) {
        dsl.update(MEMBER)
                .set(MEMBER.EMAIL, m.email())
                .set(MEMBER.DISPLAY_NAME, m.displayName())
                .set(MEMBER.LANGUAGE, m.language().code())
                .set(MEMBER.WALLET_PREFERENCE, m.walletPreference().name())
                .set(MEMBER.PAYPAL_VERIFIED, m.paypalVerified())
                .set(MEMBER.LAST_LOGIN_AT, m.lastLoginAt())
                .set(MEMBER.UPDATED_AT, m.updatedAt())
                .where(MEMBER.ID.eq(m.id()))
                .execute();
    }

    private static Member map(MemberRecord r) {
        return new Member(r.getId(), r.getPaypalPayerId(), r.getEmail(), r.getDisplayName(),
                Language.parse(r.getLanguage()).orElseThrow(), WalletPreference.parse(r.getWalletPreference()).orElseThrow(),
                r.getPaypalVerified(), r.getCreatedAt(), r.getLastLoginAt(), r.getUpdatedAt());
    }
}
