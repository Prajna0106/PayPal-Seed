package com.paypalseed.creditrecord.infrastructure.web;

import com.paypalseed.creditrecord.api.dto.CreditRecordDtos.DeskRecordView;
import com.paypalseed.creditrecord.application.usecases.RecordQueries;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Officer desk: read a member's record. It carries no hardship signal and no risk level, only the facts. */
@RestController
class DeskRecordController {

    private final RecordQueries queries;

    DeskRecordController(RecordQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/desk/members/{memberId}/record")
    DeskRecordView record(Actor actor, @PathVariable UUID memberId) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return queries.forDesk(memberId);
    }
}
