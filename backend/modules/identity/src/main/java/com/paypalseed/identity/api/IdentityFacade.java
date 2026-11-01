package com.paypalseed.identity.api;

import com.paypalseed.identity.api.dto.ConsentType;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentGrant;
import com.paypalseed.identity.api.dto.IdentityDtos.ConsentView;
import com.paypalseed.identity.api.dto.IdentityDtos.GrantResult;
import com.paypalseed.identity.api.dto.IdentityDtos.LoginResult;
import com.paypalseed.identity.api.dto.IdentityDtos.MemberView;
import com.paypalseed.identity.api.dto.IdentityDtos.PayPalLogin;
import com.paypalseed.identity.api.dto.IdentityDtos.PayoutRecipient;
import com.paypalseed.identity.api.dto.IdentityDtos.ProfileUpdate;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffUpsert;
import com.paypalseed.identity.api.dto.IdentityDtos.StaffView;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who people are and what they agreed to. Every write is idempotent on its business key (payer id, member plus type
 * plus version, staff id). This API does not check roles: the caller has already authorised the member or staff id.
 */
public interface IdentityFacade {

    /** The first login of a payer id creates the member; later logins return the same one and set the last login time. */
    LoginResult loginWithPayPal(PayPalLogin login);

    Optional<MemberView> member(UUID memberId);

    Optional<UUID> memberIdForPayer(String paypalPayerId);

    /** The PayPal account reference of the member. Personal data: callers must not log it. */
    Optional<String> paypalPayerId(UUID memberId);

    /** Payer id and email from the login record, for disbursement. Empty when the member does not exist. */
    Optional<PayoutRecipient> payoutRecipient(UUID memberId);

    /** 404 when the member does not exist; 422 when a value is not allowed. Changes only the non-null fields. */
    MemberView updateProfile(UUID memberId, ProfileUpdate update);

    /** 404 for an unknown member; 422 when the version is not the current text. */
    GrantResult grantConsent(UUID memberId, ConsentGrant grant);

    /** Revoking is always allowed and takes effect now. True when an active consent was closed. */
    boolean revokeConsent(UUID memberId, ConsentType type);

    /** The whole ledger of the member, newest first, revoked entries included. */
    List<ConsentView> consents(UUID memberId);

    boolean hasActiveConsent(UUID memberId, ConsentType type);

    StaffView upsertStaff(UUID staffId, StaffUpsert staff);

    Optional<StaffView> staff(UUID staffId);

    List<StaffView> listStaff(boolean includeInactive);

    /** Names for the maker and checker columns. Includes inactive staff; unknown ids are left out. */
    Map<UUID, String> staffDisplayNames(Collection<UUID> staffIds);
}
