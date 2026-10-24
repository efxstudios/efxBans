package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.ImportRecord;
import com.efxstudios.bans.common.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class ImportSourcesContract {
    static final ImportSources.Defaults DEFAULTS = new ImportSources.Defaults("No reason given", "IP target", "CONSOLE");
    static final UUID ALICE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    static final UUID BOB = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    static final long NOW = System.currentTimeMillis();
    static final long HOUR = 3_600_000;

    Connection c;

    abstract Connection open() throws Exception;

    abstract List<String> schema();

    @BeforeEach
    void setUp() throws Exception {
        c = open();
        try (Statement s = c.createStatement()) {
            for (String table : List.of("Punishments", "PunishmentHistory", "litebans_bans", "litebans_mutes", "litebans_warnings", "litebans_kicks", "litebans_history"))
                s.execute("DROP TABLE IF EXISTS " + table);
            for (String ddl : schema()) s.execute(ddl);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        c.close();
    }

    private static Map<String, ImportRecord> byId(List<ImportRecord> records) {
        return records.stream().collect(Collectors.toMap(ImportRecord::sourceId, Function.identity()));
    }

    private void advancedBan(String table, String name, String uuid, String reason, String type, long start, long end) throws Exception {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table + " (name, uuid, reason, operator, punishmentType, start, end, calculation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, name);
            s.setString(2, uuid);
            s.setString(3, reason);
            s.setString(4, "Moderator");
            s.setString(5, type);
            s.setLong(6, start);
            s.setLong(7, end);
            s.setString(8, null);
            s.executeUpdate();
        }
    }

    private void advancedBanBoth(String name, String uuid, String reason, String type, long start, long end) throws Exception {
        advancedBan("Punishments", name, uuid, reason, type, start, end);
        advancedBan("PunishmentHistory", name, uuid, reason, type, start, end);
    }

    @Test
    void advancedBanTypesStatesAndTargets() throws Exception {
        String alice = ALICE.toString().replace("-", "");
        String bob = BOB.toString().replace("-", "");
        advancedBanBoth("Alice", alice, "Hacking", "BAN", NOW - HOUR, -1);
        advancedBanBoth("Bob", bob, null, "TEMP_MUTE", NOW - HOUR, NOW + HOUR);
        advancedBanBoth("Alice", "10.0.0.5", "Alt farm", "TEMP_IP_BAN", NOW - HOUR, NOW + HOUR);
        advancedBan("PunishmentHistory", "Bob", bob, "Old", "TEMP_BAN", NOW - 10 * HOUR, NOW - 5 * HOUR);
        advancedBan("PunishmentHistory", "Bob", bob, "Lifted", "BAN", NOW - 9 * HOUR, -1);
        advancedBan("PunishmentHistory", "Bob", bob, "Language", "WARNING", NOW - 8 * HOUR, -1);
        advancedBan("PunishmentHistory", "Bob", bob, "AFK", "KICK", NOW - 7 * HOUR, -1);
        advancedBan("PunishmentHistory", "Bob", bob, "Just a note", "NOTE", NOW - 6 * HOUR, -1);

        advancedBanBoth("Carol", "carol", "Name only", "BAN", NOW - HOUR, -1);
        advancedBanBoth("Ghost", "ghost", "Nobody knows", "BAN", NOW - HOUR, -1);
        UUID carol = UUID.randomUUID();
        var defaults = new ImportSources.Defaults("No reason given", "IP target", "CONSOLE", name -> name.equals("Carol") ? carol : null);

        List<ImportRecord> records = ImportSources.advancedBan(c, NOW, defaults);

        assertEquals(8, records.size(), "notes and unknown names are skipped");
        assertEquals(carol, records.stream().filter(r -> r.reason().equals("Name only")).findFirst().orElseThrow().target());
        assertEquals(List.of("history:10 (Ghost)"), defaults.skipped());
        var ban = records.stream().filter(r -> r.reason().equals("Hacking")).findFirst().orElseThrow();
        assertEquals(Kind.BAN, ban.kind());
        assertEquals(ALICE, ban.target());
        assertEquals(0, ban.expires());
        assertTrue(ban.active(NOW));
        var mute = records.stream().filter(r -> r.kind() == Kind.MUTE).findFirst().orElseThrow();
        assertEquals("No reason given", mute.reason());
        assertEquals(NOW + HOUR, mute.expires());
        assertTrue(mute.active(NOW));
        var ipBan = records.stream().filter(r -> r.kind() == Kind.IPBAN).findFirst().orElseThrow();
        assertNull(ipBan.target());
        assertEquals("10.0.0.5", ipBan.ip());
        assertTrue(ipBan.active(NOW));
        var expired = records.stream().filter(r -> r.reason().equals("Old")).findFirst().orElseThrow();
        assertEquals("ACTIVE", expired.state());
        assertFalse(expired.active(NOW), "shows as expired");
        assertEquals("REMOVED", records.stream().filter(r -> r.reason().equals("Lifted")).findFirst().orElseThrow().state());
        assertEquals("EXECUTED", records.stream().filter(r -> r.kind() == Kind.WARN).findFirst().orElseThrow().state());
        assertEquals("Moderator", ban.actorName());

        assertEquals(byId(records).keySet(), byId(ImportSources.advancedBan(c, NOW, new ImportSources.Defaults("No reason given", "IP target", "CONSOLE", defaults.resolveName()))).keySet());
    }

    private void liteBans(String table, long id, UUID uuid, String ip, String reason, long time, long until, boolean active, boolean ipBan,
                          String removedBy, boolean silent) throws Exception {
        boolean kicks = table.equals("kicks");
        String sql = "INSERT INTO litebans_" + table + " (id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_scope, server_origin, silent, ipban, active"
                + (kicks ? "" : ", removed_by_name, removed_by_date") + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, '*', 'lobby', ?, ?, ?" + (kicks ? "" : ", ?, ?") + ")";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, id);
            s.setString(2, uuid == null ? "#offline#" : uuid.toString());
            s.setString(3, ip);
            s.setString(4, reason);
            s.setString(5, "00000000-0000-0000-0000-000000000000");
            s.setString(6, "Moderator");
            s.setLong(7, time);
            s.setLong(8, until);
            s.setBoolean(9, silent);
            s.setBoolean(10, ipBan);
            s.setBoolean(11, active);
            if (!kicks) {
                s.setString(12, removedBy);
                s.setTimestamp(13, removedBy == null ? null : new Timestamp(NOW - HOUR));
            }
            s.executeUpdate();
        }
    }

    @Test
    void liteBansTypesStatesNamesAndAddresses() throws Exception {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)")) {
            for (Object[] row : new Object[][]{{NOW - 9 * HOUR, "OldAlice", ALICE}, {NOW - HOUR, "Alice", ALICE}, {NOW - HOUR, "Bob", BOB}}) {
                s.setTimestamp(1, new Timestamp((long) row[0]));
                s.setString(2, (String) row[1]);
                s.setString(3, row[2].toString());
                s.setString(4, "10.0.0.7");
                s.executeUpdate();
            }
        }
        liteBans("bans", 1, ALICE, "10.0.0.7", "Hacking", NOW - HOUR, -1, true, false, null, false);
        liteBans("bans", 2, null, "10.0.0.9", "Bot farm", NOW - HOUR, NOW + HOUR, true, true, null, true);
        liteBans("bans", 3, BOB, "10.0.0.8", "Appeal accepted", NOW - 5 * HOUR, -1, false, false, "Admin", false);
        liteBans("bans", 4, BOB, "10.0.0.8", "Ran out", NOW - 5 * HOUR, NOW - HOUR, false, false, "#expired", false);
        liteBans("mutes", 1, BOB, "10.0.0.8", "Spam", NOW - HOUR, NOW + HOUR, true, false, null, false);
        liteBans("warnings", 1, BOB, "10.0.0.8", "Language", NOW - HOUR, -1, true, false, null, false);
        liteBans("kicks", 1, ALICE, "10.0.0.7", "AFK", NOW - HOUR, 0, false, false, null, false);

        Map<String, ImportRecord> records = byId(ImportSources.liteBans(c, "litebans_", NOW, DEFAULTS));

        assertEquals(7, records.size());
        assertEquals("Alice", records.get("bans:1").name(), "the newest name from history");
        assertTrue(records.get("bans:1").active(NOW));
        assertNull(records.get("bans:1").ip(), "an account ban keeps no address");
        var ipBan = records.get("bans:2");
        assertEquals(Kind.IPBAN, ipBan.kind());
        assertNull(ipBan.target());
        assertEquals("10.0.0.9", ipBan.ip());
        assertTrue(ipBan.silent());
        var lifted = records.get("bans:3");
        assertEquals("REMOVED", lifted.state());
        assertEquals("Admin", lifted.closedName());
        assertTrue(Math.abs(lifted.closedAt() - (NOW - HOUR)) < 2000, "removed_by_date");
        var ranOut = records.get("bans:4");
        assertEquals("ACTIVE", ranOut.state());
        assertFalse(ranOut.active(NOW));
        assertEquals(Kind.MUTE, records.get("mutes:1").kind());
        assertEquals("EXECUTED", records.get("warnings:1").state());
        assertEquals(Kind.KICK, records.get("kicks:1").kind());
        assertEquals("Moderator", records.get("bans:1").actorName());
    }
}
