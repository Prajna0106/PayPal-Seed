package com.paypalseed.ledger.application.usecases;

import com.paypalseed.ledger.api.LedgerFacade;
import com.paypalseed.ledger.api.dto.AccountRef;
import com.paypalseed.ledger.api.dto.PostingRequest;
import com.paypalseed.ledger.api.dto.Side;
import com.paypalseed.ledger.infrastructure.persistence.JournalRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService implements LedgerFacade {

    private final JournalRepository journal;

    LedgerService(JournalRepository journal) {
        this.journal = journal;
    }

    /** MANDATORY: a posting never commits on its own, only with the state change it records (ADR 0002). */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(PostingRequest request) {
        String hash = hash(request);
        Optional<UUID> created = journal.insertEntry(request.sourceKey(), hash, request.description(), request.businessDate());
        if (created.isEmpty()) {
            JournalRepository.ExistingEntry existing = journal.existingEntry(request.sourceKey());
            if (!existing.requestHash().equals(hash)) {
                throw new IllegalStateException("source key " + request.sourceKey() + " was already posted with different lines");
            }
            return existing.id();
        }
        UUID entryId = created.get();
        for (PostingRequest.Line line : request.lines()) {
            journal.insertLine(entryId, journal.accountId(line.account()), line.side(), line.amountCents());
        }
        return entryId;
    }

    @Override
    @Transactional(readOnly = true)
    public long balanceCents(AccountRef account) {
        long debitMinusCredit = journal.debitMinusCredit(account);
        return account.type().normalSide() == Side.DEBIT ? debitMinusCredit : -debitMinusCredit;
    }

    /** Order-independent fingerprint of the lines, so a replay with the same lines is recognised. */
    private static String hash(PostingRequest request) {
        StringBuilder canonical = new StringBuilder();
        request.lines().stream()
                .map(l -> l.account().type() + "/" + l.account().ownerId() + "/" + l.side() + "/" + l.amountCents())
                .sorted(Comparator.naturalOrder())
                .forEach(s -> canonical.append(s).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
