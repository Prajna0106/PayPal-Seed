package com.paypalseed.identity.application.usecases;

import com.paypalseed.identity.api.IdentityFacade;
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
import org.springframework.stereotype.Service;

@Service
class IdentityFacadeService implements IdentityFacade {

    private final MemberService members;
    private final ConsentService consents;
    private final StaffService staff;

    IdentityFacadeService(MemberService members, ConsentService consents, StaffService staff) {
        this.members = members;
        this.consents = consents;
        this.staff = staff;
    }

    @Override
    public LoginResult loginWithPayPal(PayPalLogin login) {
        return members.login(login);
    }

    @Override
    public Optional<MemberView> member(UUID memberId) {
        return members.find(memberId);
    }

    @Override
    public Optional<UUID> memberIdForPayer(String paypalPayerId) {
        return members.idForPayer(paypalPayerId);
    }

    @Override
    public Optional<String> paypalPayerId(UUID memberId) {
        return members.paypalPayerId(memberId);
    }

    @Override
    public Optional<PayoutRecipient> payoutRecipient(UUID memberId) {
        return members.payoutRecipient(memberId);
    }

    @Override
    public MemberView updateProfile(UUID memberId, ProfileUpdate update) {
        return members.updateProfile(memberId, update);
    }

    @Override
    public GrantResult grantConsent(UUID memberId, ConsentGrant grant) {
        return consents.grant(memberId, grant);
    }

    @Override
    public boolean revokeConsent(UUID memberId, ConsentType type) {
        return consents.revoke(memberId, type);
    }

    @Override
    public List<ConsentView> consents(UUID memberId) {
        return consents.list(memberId);
    }

    @Override
    public boolean hasActiveConsent(UUID memberId, ConsentType type) {
        return consents.hasActive(memberId, type);
    }

    @Override
    public StaffView upsertStaff(UUID staffId, StaffUpsert command) {
        return staff.upsert(staffId, command);
    }

    @Override
    public Optional<StaffView> staff(UUID staffId) {
        return staff.find(staffId);
    }

    @Override
    public List<StaffView> listStaff(boolean includeInactive) {
        return staff.list(includeInactive);
    }

    @Override
    public Map<UUID, String> staffDisplayNames(Collection<UUID> staffIds) {
        return staff.displayNames(staffIds);
    }
}
