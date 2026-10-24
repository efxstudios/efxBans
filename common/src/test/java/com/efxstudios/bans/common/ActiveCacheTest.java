package com.efxstudios.bans.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveCacheTest {
    private static Punishment punishment(long id, Kind kind, UUID target, String ip) {
        return new Punishment(id, kind, target, "Player" + id, ip, "CONSOLE", "Console", "Test", 0, 0, "ACTIVE", 0, null, null, null, false);
    }

    /** Large ban list: lookups stay index-based (this is a smoke test, not a benchmark). */
    @Test
    void largeSnapshotLookupsAreCorrectAndFast() {
        List<Punishment> rows = new ArrayList<>();
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            UUID target = UUID.randomUUID();
            targets.add(target);
            Kind kind = i % 3 == 0 ? Kind.MUTE : i % 3 == 1 ? Kind.BAN : Kind.IPBAN;
            rows.add(punishment(i + 1, kind, target, "10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255)));
        }
        ActiveCache cache = new ActiveCache();
        cache.replace(rows);

        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            Kind expected = rows.get(i).kind();
            assertEquals(i + 1, cache.find(targets.get(i), null, Set.of(expected)).orElseThrow().id());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 5_000, "100k lookups took " + millis + " ms");
        // IP bans also match other accounts on the same address.
        assertEquals(3, cache.find(UUID.randomUUID(), "10.0.0.2", Set.of(Kind.IPBAN)).orElseThrow().id());
        assertTrue(cache.find(UUID.randomUUID(), "10.0.0.1", Set.of(Kind.BAN, Kind.IPBAN)).isEmpty());

        // Tab completion for /unban and /unmute: names from memory, per kind.
        start = System.nanoTime();
        var banned = cache.names(Set.of(Kind.BAN, Kind.IPBAN));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000);
        assertEquals(66_666, banned.size()); // Every i % 3 != 0 of 100 000 is a ban or IP ban.
        assertTrue(banned.contains("Player2") && banned.contains("Player3") && !banned.contains("Player1"));
        assertTrue(cache.names(Set.of(Kind.MUTE)).contains("Player1"));
    }

    @Test
    void namesSkipAddressOnlyBansAndInactiveRows() {
        ActiveCache cache = new ActiveCache();
        Punishment removed = new Punishment(2, Kind.BAN, UUID.randomUUID(), "Gone", null, "CONSOLE", "Console", "Test", 0, 0, "REMOVED", 1, null, null, null, false);
        cache.replace(List.of(punishment(1, Kind.IPBAN, null, "10.0.0.1"), removed, punishment(3, Kind.BAN, UUID.randomUUID(), null)));
        assertEquals(Set.of("Player3"), cache.names(Set.of(Kind.BAN, Kind.IPBAN)));
    }
}
