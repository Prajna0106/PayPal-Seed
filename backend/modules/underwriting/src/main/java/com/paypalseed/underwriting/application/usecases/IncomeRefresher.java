package com.paypalseed.underwriting.application.usecases;

import com.paypalseed.platform.kernel.BusinessClock;
import com.paypalseed.platform.kernel.DomainException;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeMonthDto;
import com.paypalseed.underwriting.api.dto.UnderwritingDtos.IncomeSummary;
import com.paypalseed.underwriting.application.ports.IncomeAccountResolver;
import com.paypalseed.underwriting.application.ports.IncomeTransactionSource;
import com.paypalseed.underwriting.domain.model.IncomeBuckets;
import com.paypalseed.underwriting.domain.model.IncomeBuckets.Bucket;
import com.paypalseed.underwriting.domain.model.IncomeEntry;
import com.paypalseed.underwriting.domain.model.IncomeSource;
import com.paypalseed.underwriting.domain.model.UnderwritingErrors;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Consent;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.RefreshState;
import com.paypalseed.underwriting.infrastructure.persistence.IncomeRepository.Week;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Keeps a consented member's weekly income fresh from PayPal Transaction Search (ADR 0018), and only then.
 *
 * <p>Nothing is read unless the source is {@code TRANSACTION_SEARCH}, the member has an active income consent and
 * the stored weeks are older than {@code max-age}. The read is all or nothing: on any failure the caller gets a
 * retryable problem and the stored weeks stay as they were, so a decision is never computed from stale or partial
 * data. The PayPal call happens outside this class's own database work; the weeks are replaced in one transaction.
 *
 * <p>Two refreshes of one member that overlap both read PayPal and the later one wins; both write the same
 * 13 buckets for the same day, so the result is the same either way.
 */
@Service
public class IncomeRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(IncomeRefresher.class);

    private final IncomeRepository income;
    private final IncomeTransactionSource transactions;
    private final IncomeAccountResolver accounts;
    private final BusinessClock clock;
    private final IncomeSource source;
    private final Duration maxAge;

    @Autowired
    public IncomeRefresher(
        IncomeRepository income,
        IncomeTransactionSource transactions,
        IncomeAccountResolver accounts,
        BusinessClock clock,
        @Value("${seed.underwriting.income.source:SEEDED}") String source,
        @Value("${seed.underwriting.income.max-age:24h}") Duration maxAge
    ) {
        this(income, transactions, accounts, clock, IncomeSource.parse(source), maxAge);
    }

    public IncomeRefresher(
        IncomeRepository income,
        IncomeTransactionSource transactions,
        IncomeAccountResolver accounts,
        BusinessClock clock,
        IncomeSource source,
        Duration maxAge
    ) {
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("seed.underwriting.income.max-age must be positive");
        }
        this.income = income;
        this.transactions = transactions;
        this.accounts = accounts;
        this.clock = clock;
        this.source = source;
        this.maxAge = maxAge;
    }

    public boolean usesTransactionSearch() {
        return source == IncomeSource.TRANSACTION_SEARCH;
    }

    /** Weeks read from PayPal and not yet stored: the product of the slow, transaction-free half of a refresh. */
    public record FetchedIncome(List<Week> weeks, LocalDate anchor, Instant at) {
        public FetchedIncome {
            weeks = List.copyOf(weeks);
        }
    }

    /**
     * Reads PayPal when the source, the consent and the age of the stored weeks call for it.
     *
     * @return true when PayPal was read and the weeks replaced
     * @throws DomainException {@code paypal-account-not-linked} (422) or {@code income-source-unavailable} (503)
     */
    public boolean refreshIfStale(UUID memberId) {
        Optional<FetchedIncome> fetched = fetchIfStale(memberId, false);
        fetched.ifPresent(f -> store(memberId, f));
        return fetched.isPresent();
    }

    /**
     * The slow half of a refresh: reads PayPal and writes nothing, so a caller can do it before it opens a
     * transaction. Empty when nothing needs reading (source off, no consent, weeks still fresh).
     *
     * @param consentGivenNow true when the member is giving the consent in the request being served (a loan
     *                        application with the fair check ticked), so a missing consent does not stop the read
     */
    public Optional<FetchedIncome> fetchIfStale(UUID memberId, boolean consentGivenNow) {
        if (!usesTransactionSearch()) {
            return Optional.empty();
        }
        boolean consented = income.consent(memberId).filter(Consent::active).isPresent();
        if (!consented && !consentGivenNow) {
            return Optional.empty();
        }
        Instant now = clock.now();
        if (consented && isFresh(memberId, now)) {
            return Optional.empty();
        }
        String account = accounts.accountOf(memberId).orElseThrow(() -> new DomainException(
            UnderwritingErrors.PAYPAL_ACCOUNT_NOT_LINKED, "Link your PayPal account before Seed can read your income"));

        LocalDate today = clock.today();
        List<IncomeEntry> credits;
        try {
            credits = transactions.credits(account, IncomeBuckets.windowStart(today), today.minusDays(1));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("Income read failed for a member: {}", e.getClass().getSimpleName());
            throw new DomainException(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE, "PayPal income could not be read");
        }
        List<Week> weeks = IncomeBuckets.weekly(today, credits).stream()
            .map((Bucket b) -> new Week(b.weekStart(), b.inflowCents()))
            .toList();
        return Optional.of(new FetchedIncome(weeks, today, now));
    }

    /** The fast half: replaces the stored weeks in one short transaction. Needs an existing consent row to stamp. */
    public void store(UUID memberId, FetchedIncome fetched) {
        income.replaceFetchedWeeks(memberId, fetched.weeks(), fetched.anchor(), fetched.at());
    }

    /**
     * For code that already runs inside a transaction and so must not call PayPal: passes when the stored weeks need
     * no refresh and otherwise refuses with the same retryable problem as a failed read. The caller is expected to
     * have refreshed before it opened its transaction; this guards the case where it did not, or where the weeks
     * went stale in between, so a decision never rests on stale income.
     */
    public void requireFresh(UUID memberId) {
        if (!usesTransactionSearch()) {
            return;
        }
        boolean consented = income.consent(memberId).filter(Consent::active).isPresent();
        if (consented && !isFresh(memberId, clock.now())) {
            throw new DomainException(UnderwritingErrors.INCOME_SOURCE_UNAVAILABLE,
                "PayPal income is out of date and must be read again before this can be decided");
        }
    }

    private boolean isFresh(UUID memberId, Instant now) {
        Optional<Instant> last = income.refreshState(memberId).map(RefreshState::refreshedAt);
        // A read stamped in the future means the demo clock went back; treat it as stale rather than trusting it.
        return last.isPresent() && !last.get().isAfter(now) && !last.get().plus(maxAge).isBefore(now);
    }

    /**
     * The monthly figures for a fair check, from income read from PayPal. Empty when the real source is off, when
     * the member has no consent, or when PayPal shows no income, so the caller falls back to what it already has.
     */
    public Optional<List<IncomeMonthDto>> verifiedMonthlyIncomes(UUID memberId) {
        if (!usesTransactionSearch()) {
            return Optional.empty();
        }
        refreshIfStale(memberId);
        return storedMonthlyIncomes(memberId);
    }

    /** The same figures from the weeks already stored, with no PayPal read: for callers inside a transaction. */
    public Optional<List<IncomeMonthDto>> storedMonthlyIncomes(UUID memberId) {
        if (!usesTransactionSearch()) {
            return Optional.empty();
        }
        LocalDate today = clock.today();
        Optional<IncomeSummary> summary = IncomeSummaries.read(income, memberId, today);
        if (summary.isPresent() && summary.get().activeWeeks() > 0) {
            return Optional.of(IncomeSummaries.monthly(summary.get(), today));
        }
        return Optional.empty();
    }
}
