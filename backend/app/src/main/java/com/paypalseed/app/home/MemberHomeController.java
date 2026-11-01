package com.paypalseed.app.home;

import com.paypalseed.platform.security.Actor;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member Home page in one call. The member is the signed-in actor and nothing else: the route has no member id
 * and takes no parameters, so another member's data cannot be asked for (ADR 0018).
 */
@RestController
class MemberHomeController {

    private final MemberHomeService home;

    MemberHomeController(MemberHomeService home) {
        this.home = home;
    }

    @GetMapping("/api/v1/members/me/home")
    ResponseEntity<HomeView> home(Actor actor) {
        UUID memberId = actor.requireMember();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(home.home(memberId));
    }
}
