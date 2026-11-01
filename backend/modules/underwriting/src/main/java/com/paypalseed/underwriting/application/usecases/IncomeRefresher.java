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

    /**
     * Reads PayPal when the source, the consent and the age of the stored weeks call for it.
     *
     * @return true when PayPal was read and the weeks replaced
     * @throws DomainException {@code paypal-account-not-linked} (422) or {@code income-source-unavailable} (503)
     */
    public boolean refreshIfStale(UUID memberId) {
        if (!usesTransactionSearch()) {
            return false;
        }
        Optional<Consent> consent = income.consent(memberId).filter(Consent::active);
        if (consent.isEmpty()) {
            return false;
        }
        Instant now = clock.now();
        Optional<Instant> last = income.refreshState(memberId).map(RefreshState::refreshedAt);
        // A read stamped in the future means the demo clock went back; treat it as stale rather than trusting it.
        if (last.isPresent() && !last.get().isAfter(now) && !last.get().plus(maxAge).isBefore(now)) {
            return false;
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
        income.replaceFetchedWeeks(memberId, weeks, today, now);
        return true;
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
        LocalDate today = clock.today();
        Optional<IncomeSummary> summary = IncomeSummaries.read(income, memberId, today);
        if (summary.isPresent() && summary.get().activeWeeks() > 0) {
            return Optional.of(IncomeSummaries.monthly(summary.get(), today));
        }
        return Optional.empty();
    }
}
