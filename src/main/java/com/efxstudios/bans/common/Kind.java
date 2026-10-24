package com.efxstudios.bans.common;

import java.util.Locale;

public enum Kind {
    BAN, IPBAN, MUTE, KICK, WARN;

    /** Bans and mutes have a duration and stay active; kicks and warnings are one-off events. */
    public boolean timed() {
        return this == BAN || this == IPBAN || this == MUTE;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Bit used in the stored exemption mask. */
    public int bit() {
        return 1 << ordinal();
    }
}
