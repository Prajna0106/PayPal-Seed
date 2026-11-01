package com.paypalseed.platform.security;

import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Dev tool behind {@code ./gradlew :platform:security:mintActorToken}. Reads the first
 * {@code kid:secret} of {@code SEED_ACTOR_TOKEN_SECRETS} and prints one token for curl and Postman.
 * Arguments: {@code member <uuid>} or {@code staff <uuid> ROLE[,ROLE]}.
 */
public final class ActorTokenTool {

    private ActorTokenTool() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            usage();
            return;
        }
        String raw = System.getenv(ActorTokenSettings.SECRETS_ENV);
        if (raw == null || raw.isBlank()) {
            System.err.println("Set " + ActorTokenSettings.SECRETS_ENV + " to kid:secret first");
            System.exit(2);
        }
        ActorTokenKeys keys = ActorTokenKeys.parse(Arrays.asList(raw.split(",")));
        ActorTokenIssuer issuer = ActorTokenIssuer.signingWith(keys, Clock.systemUTC());
        UUID id = UUID.fromString(args[1]);
        switch (args[0]) {
            case "member" -> System.out.println(issuer.member(id));
            case "staff" -> {
                Set<Actor.Role> roles = EnumSet.noneOf(Actor.Role.class);
                List<String> names = args.length > 2 ? Arrays.asList(args[2].split(",")) : List.of();
                names.forEach(n -> roles.add(Actor.Role.valueOf(n.trim().toUpperCase(java.util.Locale.ROOT))));
                System.out.println(issuer.staff(id, roles));
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: member <uuid> | staff <uuid> [ROLE,ROLE]");
        System.exit(2);
    }
}
