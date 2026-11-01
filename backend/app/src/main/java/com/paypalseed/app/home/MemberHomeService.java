package com.paypalseed.app.home;

import com.paypalseed.app.home.HomeView.ErrorCode;
import com.paypalseed.app.home.HomeView.ProfileSection;
import com.paypalseed.app.home.HomeView.Section;
import com.paypalseed.app.home.HomeView.SectionError;
import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.platform.kernel.ErrorCode.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the member Home page from the public facades, one section at a time (ADR 0018).
 *
 * <p>Sections run one after another inside a single read-only transaction, so they describe one moment, and share no
 * state: this class holds only constants, and every call keeps its data in locals. A section that throws or has no
 * data becomes {@code null} plus a {@link SectionError}; the other sections are unaffected and nothing but the stable
 * code leaves this class (the exception is logged by type only, never with its message or a stack trace).
 *
 * <p>Each section runs in a savepoint of that transaction, so a section that fails (even with a database error,
 * which poisons a PostgreSQL transaction) is rolled back alone and the next section still has a usable connection.
 * The outer transaction always ends in a rollback: it wrote nothing, and a facade that threw inside it has already
 * marked it rollback-only, which would otherwise surface as an error at commit for a page that is otherwise fine.
 */
@Service
public class MemberHomeService {

    private static final Logger LOG = LoggerFactory.getLogger(MemberHomeService.class);
    private static final String DEFAULT_LANGUAGE = "en";

    private final HomeSections sections;
    private final BusinessClock clock;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate savepoint;

    MemberHomeService(HomeSections sections, BusinessClock clock, PlatformTransactionManager transactions) {
        this.sections = sections;
        this.clock = clock;
        if (transactions == null) {
            this.readOnly = null;
            this.savepoint = null;
        } else {
            this.readOnly = new TransactionTemplate(transactions);
            this.readOnly.setReadOnly(true);
            this.savepoint = new TransactionTemplate(transactions);
            this.savepoint.setPropagationBehavior(TransactionTemplate.PROPAGATION_NESTED);
            this.savepoint.setReadOnly(true);
        }
    }

    /** The page for exactly this member; there is no way to name another one. */
    public HomeView home(UUID memberId) {
        if (readOnly == null) {
            return compose(memberId);
        }
        return readOnly.execute(status -> {
            HomeView view = compose(memberId);
            status.setRollbackOnly();
            return view;
        });
    }

    private HomeView compose(UUID memberId) {
        List<SectionError> errors = new ArrayList<>();
        ProfileSection profile = read(Section.profile, errors, () -> sections.profile(memberId));
        HomeSections.Context context = new HomeSections.Context(memberId, clock.today(),
            profile == null || profile.language() == null ? DEFAULT_LANGUAGE : profile.language());
        var savings = read(Section.savings, errors, () -> sections.savings(context));
        var lending = read(Section.lending, errors, () -> sections.lending(context));
        var underwriting = read(Section.underwriting, errors, () -> sections.underwriting(context));
        var creditRecord = read(Section.creditRecord, errors, () -> sections.creditRecord(context));
        var hardship = read(Section.hardship, errors, () -> sections.hardship(context));
        return new HomeView(context.today(), profile, savings, lending, underwriting, creditRecord, hardship, errors);
    }

    private <T> T read(Section section, List<SectionError> errors, Supplier<Optional<T>> source) {
        try {
            Optional<T> value = savepoint == null ? source.get() : savepoint.execute(status -> source.get());
            if (value.isEmpty()) {
                errors.add(new SectionError(section, ErrorCode.NO_DATA));
                return null;
            }
            return value.get();
        } catch (DomainException e) {
            // A module saying "not found" has nothing for this member; any other refusal means it could not answer.
            boolean none = e.errorCode().kind() == Kind.NOT_FOUND;
            if (!none) {
                LOG.warn("Home section {} refused: {}", section, e.errorCode().code());
            }
            errors.add(new SectionError(section, none ? ErrorCode.NO_DATA : ErrorCode.UNAVAILABLE));
            return null;
        } catch (RuntimeException e) {
            LOG.warn("Home section {} failed: {}", section, e.getClass().getSimpleName());
            errors.add(new SectionError(section, ErrorCode.UNAVAILABLE));
            return null;
        }
    }
}
