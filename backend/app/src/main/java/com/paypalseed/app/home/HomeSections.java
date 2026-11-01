package com.paypalseed.app.home;

import com.paypalseed.app.home.HomeView.CreditRecordSection;
import com.paypalseed.app.home.HomeView.HardshipSection;
import com.paypalseed.app.home.HomeView.LendingSection;
import com.paypalseed.app.home.HomeView.ProfileSection;
import com.paypalseed.app.home.HomeView.SavingsSection;
import com.paypalseed.app.home.HomeView.UnderwritingSection;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * One read per section. An empty result means the module has nothing for this member yet; an exception means the
 * module failed. {@link MemberHomeService} turns both into a section error and keeps the other sections.
 */
interface HomeSections {

    /** What a section may depend on: who, which business date, and the language to write hints in. */
    record Context(UUID memberId, LocalDate today, String language) {
    }

    Optional<ProfileSection> profile(UUID memberId);

    Optional<SavingsSection> savings(Context context);

    Optional<LendingSection> lending(Context context);

    Optional<UnderwritingSection> underwriting(Context context);

    Optional<CreditRecordSection> creditRecord(Context context);

    Optional<HardshipSection> hardship(Context context);
}
