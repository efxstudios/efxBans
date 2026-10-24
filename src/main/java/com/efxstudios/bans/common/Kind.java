package com.efxstudios.bans.common;

import java.util.Locale;

public enum Kind {
    BAN, IPBAN, MUTE, KICK, WARN;

    public boolean timed() {
        return this == BAN || this == IPBAN || this == MUTE;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int bit() {
        return 1 << ordinal();
    }
}
