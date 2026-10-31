package com.paypalseed.ledger.api.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One balanced journal entry. {@code sourceKey} identifies the business fact (a PayPal transaction
 * id, a disbursement, a refund); the ledger posts each source key exactly once.
 */
public record PostingRequest(String sourceKey, String description, LocalDate businessDate, List<Line> lines) {

    public record Line(AccountRef account, Side side, long amountCents) {
        public Line {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(side, "side");
            if (amountCents <= 0) {
                throw new IllegalArgumentException("posting line amounts must be positive");
            }
        }
    }

    public PostingRequest {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(businessDate, "businessDate");
        lines = List.copyOf(lines);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two lines");
        }
        long debits = 0;
        long credits = 0;
        for (Line line : lines) {
            if (line.side() == Side.DEBIT) {
                debits += line.amountCents();
            } else {
                credits += line.amountCents();
            }
        }
        if (debits != credits) {
            throw new IllegalArgumentException("unbalanced entry " + sourceKey + ": debits " + debits + " credits " + credits);
        }
    }

    /** Builder that drops zero-amount lines, so callers can post computed deltas directly. */
    public static Builder builder(String sourceKey, String description, LocalDate businessDate) {
        return new Builder(sourceKey, description, businessDate);
    }

    public static final class Builder {
        private final String sourceKey;
        private final String description;
        private final LocalDate businessDate;
        private final List<Line> lines = new ArrayList<>();

        private Builder(String sourceKey, String description, LocalDate businessDate) {
            this.sourceKey = sourceKey;
            this.description = description;
            this.businessDate = businessDate;
        }

        public Builder debit(AccountRef account, long amountCents) {
            return line(account, Side.DEBIT, amountCents);
        }

        public Builder credit(AccountRef account, long amountCents) {
            return line(account, Side.CREDIT, amountCents);
        }

        private Builder line(AccountRef account, Side side, long amountCents) {
            if (amountCents < 0) {
                throw new IllegalArgumentException("negative amount on " + account);
            }
            if (amountCents > 0) {
                lines.add(new Line(account, side, amountCents));
            }
            return this;
        }

        public PostingRequest build() {
            return new PostingRequest(sourceKey, description, businessDate, lines);
        }
    }
}
