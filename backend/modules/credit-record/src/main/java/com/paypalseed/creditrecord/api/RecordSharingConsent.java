package com.paypalseed.creditrecord.api;

import java.util.UUID;

/**
 * What the credit record asks of the module that joins it to the consent ledger: has this member agreed to share their
 * record, at the consent text in force now (ADR 0025). The record imports no other business module (ADR 0014), so the
 * wiring module implements this from identity's ledger. It lives in {@code api} so that module may implement it.
 */
public interface RecordSharingConsent {

    /** False for a member with no active consent at the current version, and for a member nobody knows. */
    boolean granted(UUID memberId);
}
