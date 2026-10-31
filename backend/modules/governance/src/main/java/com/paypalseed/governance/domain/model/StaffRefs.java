package com.paypalseed.governance.domain.model;

import java.util.UUID;

/** Pseudonymous staff reference for events and views: stable per person, but not the staff id itself. */
public final class StaffRefs {

    private StaffRefs() {
    }

    public static String of(UUID staffId) {
        return "staff:" + AuditHash.sha256Hex("governance-staff:" + staffId).substring(0, 16);
    }
}
