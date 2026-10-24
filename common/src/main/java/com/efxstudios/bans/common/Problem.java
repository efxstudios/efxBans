package com.efxstudios.bans.common;

import java.util.Map;

/** An expected user error. The key selects a configurable message under {@code errors.} in messages.yml. */
public final class Problem extends IllegalArgumentException {
    private final String key;
    private final Map<String, String> values;

    public Problem(String key) {
        this(key, Map.of());
    }

    public Problem(String key, Map<String, String> values) {
        super(key);
        this.key = key;
        this.values = Map.copyOf(values);
    }

    public String key() {
        return "errors." + key;
    }

    public Map<String, String> values() {
        return values;
    }
}
