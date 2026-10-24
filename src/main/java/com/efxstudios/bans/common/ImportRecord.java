package com.efxstudios.bans.common;

import java.util.UUID;

public record ImportRecord(String sourceId, Kind kind, UUID target, String name, String ip, String actorName, String reason,
                           long created, long expires, String state, long closedAt, String closedName, boolean silent) {

    public ImportRecord {
        if (kind == Kind.IPBAN ? ip == null && target == null : target == null)
            throw new IllegalArgumentException(sourceId + ": no target");
        if (!state.equals("ACTIVE") && !state.equals("REMOVED") && !state.equals("EXECUTED"))
            throw new IllegalArgumentException(sourceId + ": unknown state " + state);
    }

    public boolean active(long now) {
        return kind.timed() && state.equals("ACTIVE") && (expires == 0 || expires > now);
    }
}
