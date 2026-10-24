package com.efxstudios.bans.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable snapshots, indexed by UUID and IP: chat checks never run SQL or scan the whole table. */
public final class ActiveCache {
    private record Index(Map<Long, Punishment> ids, Map<UUID, List<Punishment>> accounts, Map<String, List<Punishment>> ips) {}

    private volatile Index index = new Index(Map.of(), Map.of(), Map.of());

    public synchronized void replace(Collection<Punishment> rows) {
        publish(rows);
    }

    public synchronized void apply(Collection<Store.Change> changes) {
        Map<Long, Punishment> rows = new HashMap<>(index.ids);
        long now = System.currentTimeMillis();
        rows.values().removeIf(p -> !p.active(now));
        for (Store.Change change : changes) {
            Punishment p = change.punishment();
            if (p.active(now)) rows.put(p.id(), p);
            else rows.remove(p.id());
        }
        publish(rows.values());
    }

    private void publish(Collection<Punishment> rows) {
        Map<Long, Punishment> ids = new HashMap<>();
        Map<UUID, List<Punishment>> accounts = new HashMap<>();
        Map<String, List<Punishment>> ips = new HashMap<>();
        for (Punishment p : rows) {
            ids.put(p.id(), p);
            if (p.target() != null) accounts.computeIfAbsent(p.target(), k -> new ArrayList<>()).add(p);
            if (p.kind() == Kind.IPBAN && p.ip() != null) ips.computeIfAbsent(p.ip(), k -> new ArrayList<>()).add(p);
        }
        accounts.replaceAll((k, v) -> List.copyOf(v));
        ips.replaceAll((k, v) -> List.copyOf(v));
        index = new Index(Map.copyOf(ids), Map.copyOf(accounts), Map.copyOf(ips));
    }

    /** Newest active punishment of the given kinds for this account or, for IP bans, this address. */
    public Optional<Punishment> find(UUID uuid, String ip, Set<Kind> kinds) {
        Index snapshot = index;
        long now = System.currentTimeMillis();
        return Stream.concat(
                        snapshot.accounts.getOrDefault(uuid, List.of()).stream(),
                        snapshot.ips.getOrDefault(ip == null ? "" : ip, List.of()).stream())
                .filter(p -> kinds.contains(p.kind()) && p.active(now))
                .max(Comparator.comparingLong(Punishment::id));
    }
}
