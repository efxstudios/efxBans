package com.efxstudios.bans.common;

import java.util.UUID;

/**
 * One row of efxb_punishments. {@code target} is null for an IP ban issued directly against an address.
 * {@code expires} is 0 for permanent punishments.
 */
public record Punishment(long id, Kind kind, UUID target, String name, String ip,
                         String actor, String actorName, String reason, long created, long expires,
                         String state, long closedAt, String closedBy, String closedName, String closeReason,
                         boolean silent) {

    public boolean active(long now) {
        return kind.timed() && state.equals("ACTIVE") && (expires == 0 || expires > now);
    }

    /** Stored state, except an elapsed ACTIVE row is reported as EXPIRED. */
    public String status(long now) {
        return state.equals("ACTIVE") && expires > 0 && expires <= now ? "EXPIRED" : state;
    }

    /** An IP ban matches its account and its address; every other kind matches the account only. */
    public boolean matches(UUID uuid, String address) {
        boolean account = target != null && target.equals(uuid);
        if (kind != Kind.IPBAN) return account;
        return account || (ip != null && ip.equals(address));
    }
}
