package com.efxstudios.bans.common;

import java.util.UUID;

/**
 * One punishment read from another plugin, ready to be stored by {@link Store#importBatch}.
 *
 * @param sourceId  stable ID within the source (e.g. "bans:42"); importing the same ID twice does nothing
 * @param target    the punished account, or null for an IP ban without an account
 * @param expires   0 for permanent
 * @param state     ACTIVE, REMOVED or EXECUTED (kicks and warnings); an ACTIVE row whose time is up shows as expired
 */
public record ImportRecord(String sourceId, Kind kind, UUID target, String name, String ip, String actorName, String reason,
                           long created, long expires, String state, long closedAt, String closedName, boolean silent) {

    public ImportRecord {
        if (kind == Kind.IPBAN ? ip == null && target == null : target == null)
            throw new IllegalArgumentException(sourceId + ": no target");
        if (!state.equals("ACTIVE") && !state.equals("REMOVED") && !state.equals("EXECUTED"))
            throw new IllegalArgumentException(sourceId + ": unknown state " + state);
    }

    /** Whether this row would be in effect right now. */
    public boolean active(long now) {
        return kind.timed() && state.equals("ACTIVE") && (expires == 0 || expires > now);
    }
}
