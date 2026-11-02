package com.paypalseed.creditrecord.infrastructure.web;

import com.paypalseed.creditrecord.application.usecases.GetTrustRecordUseCase;
import com.paypalseed.creditrecord.application.usecases.GetTrustRecordUseCase.TrustRecordResponse;
import com.paypalseed.platform.security.Actor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CreditRecordController {

    private final GetTrustRecordUseCase getTrustRecord;

    public CreditRecordController(GetTrustRecordUseCase getTrustRecord) {
        this.getTrustRecord = getTrustRecord;
    }

    @GetMapping("/api/v1/members/me/trust-record")
    public TrustRecordResponse getMyTrustRecord(Actor actor) {
        actor.requireMember();
        return getTrustRecord.getTrustRecord(actor.id());
    }
}
