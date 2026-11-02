package com.paypalseed.creditrecord.application.usecases;

import com.paypalseed.creditrecord.api.CreditRecordFacade;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.CreateShare;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryPage;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.IngestOutcome;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.LevelView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareCreated;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.VerifiedRecord;
import com.paypalseed.creditrecord.api.dto.RecordFact;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreditRecordFacadeService implements CreditRecordFacade {

    private final RecordQueries queries;
    private final RecordIngest ingest;
    private final SavingsMonthClose monthClose;
    private final ShareService shares;

    public CreditRecordFacadeService(RecordQueries queries, RecordIngest ingest, SavingsMonthClose monthClose,
                                     ShareService shares) {
        this.queries = queries;
        this.ingest = ingest;
        this.monthClose = monthClose;
        this.shares = shares;
    }

    @Override
    public RecordView record(UUID memberId) {
        return queries.record(memberId);
    }

    @Override
    public LevelView levelOf(UUID memberId) {
        return queries.levelOf(memberId);
    }

    @Override
    public HistoryPage history(UUID memberId, Long before, int limit) {
        return queries.history(memberId, before, limit);
    }

    @Override
    public IngestOutcome ingest(RecordFact fact) {
        return ingest.ingest(fact);
    }

    @Override
    public int closeSavingMonths(LocalDate asOf, int limit) {
        return monthClose.closeThrough(asOf, limit);
    }

    @Override
    public ShareCreated createShare(UUID memberId, CreateShare request, String idempotencyKey) {
        return shares.create(memberId, request, idempotencyKey);
    }

    @Override
    public List<ShareView> shares(UUID memberId) {
        return shares.list(memberId);
    }

    @Override
    public void revokeShare(UUID memberId, UUID shareId) {
        shares.revoke(memberId, shareId);
    }

    @Override
    public VerifiedRecord verify(String token) {
        return shares.verify(token);
    }
}
