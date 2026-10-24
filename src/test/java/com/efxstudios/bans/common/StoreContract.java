package com.efxstudios.bans.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class StoreContract {
    static final Set<Kind> BANS = Set.of(Kind.BAN, Kind.IPBAN);

    Store store;
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    abstract DatabaseSettings settings();

    abstract void reset() throws Exception;

    @BeforeEach
    void open() throws Exception {
        reset();
        store = new Store(settings());
        store.remember(a, "Alice", "127.0.0.1", 0);
        store.remember(b, "Bob", "127.0.0.1", 0);
    }

    @AfterEach
    void close() {
        store.close();
    }

    void reopen() throws Exception {
        store.close();
        store = new Store(settings());
    }

    Punishment issue(Kind kind, UUID uuid, String ip, long duration) throws Exception {
        String name = uuid == null ? "IP hedefi" : uuid.equals(a) ? "Alice" : "Bob";
        return store.issue(kind, uuid, name, ip, "CONSOLE", "Console", "Sebep <red> ' ", duration);
    }

    static boolean active(Punishment p) {
        return p.active(System.currentTimeMillis());
    }

    @Test
    void nameUnbanRemovesAccountBanAndIpBanButNotOtherSharedIpPunishments() throws Exception {
        var ban = issue(Kind.BAN, a, null, 0);
        var ipban = issue(Kind.IPBAN, a, "127.0.0.1", 0);
        var other = issue(Kind.IPBAN, b, "127.0.0.1", 0);
        var literal = issue(Kind.IPBAN, null, "127.0.0.2", 0);

        var removed = store.remove(null, a, null, BANS, false, "staff", "Staff", "Hata");

        assertEquals(Set.of(ban.id(), ipban.id()), new HashSet<>(removed.stream().map(Punishment::id).toList()));
        assertTrue(active(store.get(other.id()).orElseThrow()));
        assertTrue(active(store.get(literal.id()).orElseThrow()));
    }

    @Test
    void idOnlyRemovesExactPunishmentAndUndoPreservesBothAuditActions() throws Exception {
        var ban = issue(Kind.BAN, a, null, 0);
        var mute = issue(Kind.MUTE, a, null, 0);

        assertEquals(1, store.remove(ban.id(), null, null, BANS, false, "staff1", "Staff 1", "İtiraz").size());
        store.remove(ban.id(), null, null, EnumSet.allOf(Kind.class), true, "staff2", "Staff 2", "Yanlış ceza");

        assertEquals("REVOKED", store.get(ban.id()).orElseThrow().state());
        assertTrue(active(store.get(mute.id()).orElseThrow()));
        assertEquals(List.of("ISSUE", "REMOVE", "UNDO"), store.audit(ban.id()).stream().map(Store.Change::action).toList());
        assertEquals("İtiraz", store.audit(ban.id()).get(1).reason());
        assertEquals("Staff 2", store.get(ban.id()).orElseThrow().closedName());
    }

    @Test
    void idRemovalOfAddressOnlyIpBanRequiresAddressAccess() throws Exception {
        var literal = issue(Kind.IPBAN, null, "127.0.0.2", 0);
        var account = issue(Kind.BAN, a, null, 0);

        assertThrows(Problem.class, () -> store.remove(literal.id(), null, null, BANS, false, "staff", "Staff", "", false));
        assertThrows(Problem.class, () -> store.remove(literal.id(), null, null, EnumSet.allOf(Kind.class), true, "staff", "Staff", "", false));
        assertTrue(active(store.get(literal.id()).orElseThrow()));

        assertEquals(1, store.remove(account.id(), null, null, BANS, false, "staff", "Staff", "", false).size());
        assertEquals(1, store.remove(literal.id(), null, null, BANS, false, "staff", "Staff", "", true).size());
    }

    @Test
    void restartKeepsIdsAuditAndNameChangesOnSameUuid() throws Exception {
        var warn = issue(Kind.WARN, a, null, 0);
        store.remove(warn.id(), null, null, EnumSet.allOf(Kind.class), true, "staff", "Staff", "Yanlış");
        store.remember(a, "NewAlice", "127.0.0.3", 0);
        reopen();

        assertEquals(a, store.profile("NewAlice").orElseThrow().uuid());
        assertEquals(a, store.profile("Alice").orElseThrow().uuid());
        assertEquals(warn.id(), store.history(a, 1).getFirst().id());
        assertTrue(issue(Kind.KICK, a, null, 0).id() > warn.id());
        assertEquals(1, store.alts(a).size());
    }

    @Test
    void duplicateActivePunishmentRollsBackWithoutPhantomEvent() throws Exception {
        issue(Kind.MUTE, a, null, 0);
        long before = store.snapshot().cursor();

        assertThrows(IllegalArgumentException.class, () -> issue(Kind.MUTE, a, null, 0));

        assertEquals(before, store.snapshot().cursor());
        assertTrue(store.changes(before).isEmpty());
    }

    @Test
    void offlineWarningsRemainPendingAcrossRestartAndUndoneWarningsAreNotDelivered() throws Exception {
        var first = issue(Kind.WARN, a, null, 0);
        var revoked = issue(Kind.WARN, a, null, 0);
        store.remove(revoked.id(), null, null, Set.of(Kind.WARN), true, "staff", "Staff", "Yanlış");
        reopen();

        assertEquals(List.of(first.id()), store.pendingWarnings(a).stream().map(Punishment::id).toList());
        store.acknowledgeWarnings(b, List.of(first.id()));
        assertEquals(1, store.pendingWarnings(a).size());
        store.acknowledgeWarnings(a, List.of(first.id()));
        assertTrue(store.pendingWarnings(a).isEmpty());
    }

    @Test
    void expiryIsEnforcedWithoutDeletingHistoryOrRequiringSweep() throws Exception {
        var mute = issue(Kind.MUTE, a, null, 1);
        Thread.sleep(20);

        assertFalse(active(store.get(mute.id()).orElseThrow()));
        assertEquals("EXPIRED", store.history(a, 1).getFirst().status(System.currentTimeMillis()));
        assertTrue(store.activeFor(a, "127.0.0.1").isEmpty());
        assertTrue(store.snapshot().active().isEmpty());
    }

    @Test
    void ipBanMatchesBothAccountAfterIpChangeAndOtherAccountsOnSameIp() throws Exception {
        var ban = issue(Kind.IPBAN, a, "127.0.0.1", 0);

        assertTrue(ban.matches(a, "127.0.0.99"));
        assertTrue(ban.matches(b, "127.0.0.1"));
        assertFalse(ban.matches(b, "127.0.0.99"));
        assertEquals(ban.id(), store.activeFor(a, "127.0.0.99").getFirst().id());
        assertEquals(ban.id(), store.activeFor(b, "127.0.0.1").getFirst().id());
    }

    @Test
    void concurrentWritersProduceDurableOrderedChanges() throws Exception {
        try (Store second = new Store(settings()); ExecutorService threads = Executors.newFixedThreadPool(4)) {
            List<Future<Long>> writes = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                Store selected = i % 2 == 0 ? store : second;
                writes.add(threads.submit(() -> selected.issue(Kind.WARN, a, "Alice", null, "console", "Console", "Test", 0).id()));
            }
            Set<Long> ids = new HashSet<>();
            for (var write : writes) ids.add(write.get(10, TimeUnit.SECONDS));
            assertEquals(30, ids.size());

            var changes = store.changes(0);
            assertEquals(30, changes.size());
            for (int i = 1; i < changes.size(); i++) assertTrue(changes.get(i).sequence() > changes.get(i - 1).sequence());
            assertEquals(changes.getLast().sequence(), store.snapshot().cursor());
        }
    }

    @Test
    void inquiryOnlyReturnsActiveBanIpBanAndMuteByAccountIpAndExactId() throws Exception {
        var ban = issue(Kind.BAN, a, "127.0.0.1", 0);
        var mute = issue(Kind.MUTE, a, "127.0.0.1", 0);
        var ipban = issue(Kind.IPBAN, a, "127.0.0.1", 0);
        issue(Kind.WARN, a, "127.0.0.1", 0);

        var account = store.inquiry(null, a, null, 1);
        assertEquals(List.of(ipban.id(), mute.id(), ban.id()), account.entries().stream().map(Punishment::id).toList());
        assertEquals(3, store.inquiry(null, null, "127.0.0.1", 1).total());
        assertEquals(List.of(mute.id()), store.inquiry(mute.id(), null, null, 1).entries().stream().map(Punishment::id).toList());

        store.remove(mute.id(), null, null, Set.of(Kind.MUTE), false, "staff", "Staff", "");
        assertTrue(store.inquiry(mute.id(), null, null, 1).entries().isEmpty());
        assertEquals(2, store.inquiry(null, a, null, 1).total());

        store.remove(ban.id(), null, null, Set.of(Kind.BAN), true, "staff", "Staff", "");
        assertTrue(store.inquiry(ban.id(), null, null, 1).entries().isEmpty());

        var expired = issue(Kind.MUTE, b, "127.0.0.1", 1);
        Thread.sleep(20);
        assertTrue(store.inquiry(expired.id(), null, null, 1).entries().isEmpty());
        assertEquals(1, store.inquiry(null, null, "127.0.0.1", 1).total());
        assertEquals(4, store.history(a, 1).size());
        assertEquals(0, store.inquiry(null, b, null, 1).total());
    }

    @Test
    void activeListsPaginateAndExcludeExpiredRemovedAndOtherKinds() throws Exception {
        for (int i = 0; i < 12; i++) issue(Kind.BAN, UUID.randomUUID(), null, 0);
        var removed = issue(Kind.BAN, a, null, 0);
        store.remove(removed.id(), null, null, Set.of(Kind.BAN), false, "staff", "Staff", "Test");
        issue(Kind.MUTE, a, null, 0);
        issue(Kind.BAN, b, null, 1);
        Thread.sleep(20);

        var first = store.activePage(BANS, 1);
        var last = store.activePage(BANS, 999);
        assertEquals(12, first.total());
        assertEquals(2, first.pages());
        assertEquals(10, first.entries().size());
        assertEquals(2, last.number());
        assertEquals(2, last.entries().size());
        assertTrue(first.entries().getLast().id() > last.entries().getFirst().id());
        assertEquals(1, store.activePage(Set.of(Kind.MUTE), 1).total());

        for (int i = 0; i < 5; i++) {
            var p = issue(Kind.BAN, a, null, 0);
            store.remove(p.id(), null, null, Set.of(Kind.BAN), false, "staff", "Staff", "Test");
        }
        var inquiry = store.inquiry(null, a, null, 999);
        assertEquals(1, inquiry.total());
        assertEquals(1, inquiry.pages());
        assertEquals(1, inquiry.number());
        assertEquals(1, inquiry.entries().size());
    }

    @Test
    void silentFlagSurvivesRestartAndChangeFeed() throws Exception {
        long cursor = store.snapshot().cursor();
        var mute = store.issue(Kind.MUTE, b, "Bob", null, "console", "Console", "Sessiz test", Times.parse("1h"), true);

        assertTrue(mute.silent());
        assertTrue(store.changes(cursor).getFirst().punishment().silent());
        reopen();
        assertTrue(store.get(mute.id()).orElseThrow().silent());
        assertTrue(store.snapshot().active().stream().anyMatch(p -> p.id() == mute.id() && p.silent()));
    }

    @Test
    void cacheDoesNotRemoveOtherActiveRestrictions() throws Exception {
        var ban = issue(Kind.BAN, a, null, 0);
        var mute = issue(Kind.MUTE, a, null, 0);
        ActiveCache cache = new ActiveCache();
        cache.replace(store.snapshot().active());
        long cursor = store.snapshot().cursor();

        store.remove(ban.id(), null, null, Set.of(Kind.BAN), false, "console", "Console", "Test");
        cache.apply(store.changes(cursor));

        assertTrue(cache.find(a, "127.0.0.1", Set.of(Kind.BAN)).isEmpty());
        assertEquals(mute.id(), cache.find(a, null, Set.of(Kind.MUTE)).orElseThrow().id());
    }

    static ImportRecord imported(String id, Kind kind, UUID target, String ip, long expires, String state) {
        return new ImportRecord(id, kind, target, target == null ? "IP target" : "Imported", ip, "OldStaff", "Old reason",
                System.currentTimeMillis() - 60_000, expires, state, 0, null, false);
    }

    @Test
    void importIsRepeatableAndKeepsOneActivePunishmentPerKind() throws Exception {
        UUID c = UUID.randomUUID();
        long future = System.currentTimeMillis() + 3_600_000;
        long cursor = store.snapshot().cursor();
        var existing = issue(Kind.BAN, a, null, 0);
        List<ImportRecord> batch = List.of(
                imported("bans:1", Kind.BAN, c, null, future, "ACTIVE"),
                imported("bans:2", Kind.BAN, a, null, 0, "ACTIVE"),
                imported("bans:3", Kind.BAN, a, null, 0, "REMOVED"),
                imported("bans:4", Kind.IPBAN, null, "10.1.2.3", 0, "ACTIVE"),
                imported("warnings:1", Kind.WARN, c, null, 0, "EXECUTED"));

        assertEquals(new Store.ImportResult(4, 0, 1), store.importBatch("test", batch));
        assertEquals(new Store.ImportResult(0, 4, 1), store.importBatch("test", batch));
        assertEquals(3, store.importedCount("test", List.of("bans:1", "bans:3", "bans:4", "bans:9")));

        assertEquals("Imported", store.profile(c.toString()).orElseThrow().name());
        assertEquals(1, store.activeFor(c, null).size());
        assertEquals(1, store.activeFor(UUID.randomUUID(), "10.1.2.3").size());
        assertEquals(existing.id(), store.activeFor(a, null).getFirst().id());
        assertEquals(2, store.history(a, 1).size());
        assertTrue(store.pendingWarnings(c).isEmpty(), "old warnings are not delivered again");
        assertTrue(store.changes(cursor).stream().filter(ch -> ch.punishment().id() != existing.id()).allMatch(ch -> ch.action().equals("IMPORT")));
        assertEquals("Old reason", store.activeFor(c, null).getFirst().reason());
    }

    @Test
    void userTextIsStoredLiterally() throws Exception {
        String reason = "'; DROP TABLE efxb_punishments; -- <red>{player}</red> ğüşiöç 🙂";
        var p = store.issue(Kind.WARN, a, "Alice", null, "console", "Console", reason, 0);
        reopen();
        assertEquals(reason, store.get(p.id()).orElseThrow().reason());
    }
}
