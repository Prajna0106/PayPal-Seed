package com.paypalseed.creditrecord.infrastructure.web;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.CreateShare;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.HistoryPage;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.RecordView;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareCreated;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareDetail;
import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.ShareView;
import com.paypalseed.creditrecord.application.usecases.RecordQueries;
import com.paypalseed.creditrecord.application.usecases.ShareService;
import com.paypalseed.platform.security.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in member's own record and share links. Every route reads the member from the session, never from
 * the path, so there is no way to ask for someone else's record.
 */
@RestController
@Validated
class MemberRecordController {

    static final int DEFAULT_DAYS = 7;

    record CreateShareRequest(@Min(ShareService.MIN_DAYS) @Max(ShareService.MAX_DAYS) Integer expiresInDays,
                              ShareDetail detail) {
    }

    private final RecordQueries queries;
    private final ShareService shares;

    MemberRecordController(RecordQueries queries, ShareService shares) {
        this.queries = queries;
        this.shares = shares;
    }

    @GetMapping("/api/v1/members/me/record")
    RecordView record(Actor actor) {
        return queries.record(actor.requireMember());
    }

    @GetMapping("/api/v1/members/me/record/history")
    HistoryPage history(Actor actor, @RequestParam(required = false) @Min(1) Long before,
                        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return queries.history(actor.requireMember(), before, limit);
    }

    @PostMapping("/api/v1/members/me/record/shares")
    ResponseEntity<ShareCreated> createShare(Actor actor, @Valid @RequestBody CreateShareRequest body,
                                             @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String key) {
        int days = body.expiresInDays() == null ? DEFAULT_DAYS : body.expiresInDays();
        ShareCreated created = shares.create(actor.requireMember(), new CreateShare(days, body.detail()), key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(created);
    }

    @GetMapping("/api/v1/members/me/record/shares")
    List<ShareView> listShares(Actor actor) {
        return shares.list(actor.requireMember());
    }

    @DeleteMapping("/api/v1/members/me/record/shares/{shareId}")
    ResponseEntity<Void> revoke(Actor actor, @PathVariable UUID shareId) {
        shares.revoke(actor.requireMember(), shareId);
        return ResponseEntity.noContent().build();
    }
}
