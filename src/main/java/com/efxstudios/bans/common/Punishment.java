package com.efxstudios.bans.common;

import java.util.UUID;

public record Punishment(long id, Kind kind, UUID target, String name, String ip,
                         String actor, String actorName, String reason, long created, long expires,
                         String state, long closedAt, String closedBy, String closedName, String closeReason,
                         boolean silent) {

    public boolean active(long now) {
        return kind.timed() && state.equals("ACTIVE") && (expires == 0 || expires > now);
    }

    public String status(long now) {
        return state.equals("ACTIVE") && expires > 0 && expires <= now ? "EXPIRED" : state;
    }

    public boolean matches(UUID uuid, String address) {
        boolean account = target != null && target.equals(uuid);
        if (kind != Kind.IPBAN) return account;
        return account || (ip != null && ip.equals(address));
    }
}
