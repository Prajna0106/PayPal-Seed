package com.paypalseed.deskquery.infrastructure.web;

import com.paypalseed.deskquery.application.usecases.GetDeskBriefingUseCase;
import com.paypalseed.deskquery.application.usecases.GetDeskBriefingUseCase.DeskBriefingResponse;
import com.paypalseed.platform.security.Actor;
import com.paypalseed.platform.security.Actor.Role;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeskQueryController {

    private final GetDeskBriefingUseCase getDeskBriefing;

    public DeskQueryController(GetDeskBriefingUseCase getDeskBriefing) {
        this.getDeskBriefing = getDeskBriefing;
    }

    @GetMapping("/api/v1/desk/analyst/briefing")
    public DeskBriefingResponse getBriefing(Actor actor) {
        actor.requireAnyRole(Role.OFFICER, Role.CHECKER, Role.ADMIN);
        return getDeskBriefing.getBriefing();
    }
}
