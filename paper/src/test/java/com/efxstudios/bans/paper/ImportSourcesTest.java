package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.ImportRecord;
import com.efxstudios.bans.common.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The readers on SQLite, plus the vanilla JSON format. */
class ImportSourcesTest extends ImportSourcesContract {
    @TempDir
    Path directory;

    @Override
    Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("source.db"));
    }

    @Override
    List<String> schema() {
        String advancedBan = " (id INTEGER PRIMARY KEY AUTOINCREMENT, name VARCHAR(16), uuid VARCHAR(35), reason VARCHAR(255), operator VARCHAR(16),"
                + " punishmentType VARCHAR(16), start BIGINT, end BIGINT, calculation VARCHAR(50))";
        String liteBans = " (id INTEGER PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048), banned_by_uuid VARCHAR(36), banned_by_name VARCHAR(128),"
                + " removed_by_uuid VARCHAR(36), removed_by_name VARCHAR(128), removed_by_date TIMESTAMP, removed_by_reason VARCHAR(2048), time BIGINT, until BIGINT,"
                + " server_scope VARCHAR(32), server_origin VARCHAR(32), silent BOOLEAN, ipban BOOLEAN, ipban_wildcard BOOLEAN, active BOOLEAN";
        return List.of(
                "CREATE TABLE Punishments" + advancedBan,
                "CREATE TABLE PunishmentHistory" + advancedBan,
                "CREATE TABLE litebans_bans" + liteBans + ")",
                "CREATE TABLE litebans_mutes" + liteBans + ")",
                "CREATE TABLE litebans_warnings" + liteBans + ", warned BOOLEAN)",
                "CREATE TABLE litebans_kicks (id INTEGER PRIMARY KEY, uuid VARCHAR(36), ip VARCHAR(45), reason VARCHAR(2048), banned_by_uuid VARCHAR(36),"
                        + " banned_by_name VARCHAR(128), time BIGINT, until BIGINT, server_scope VARCHAR(32), server_origin VARCHAR(32), silent BOOLEAN, ipban BOOLEAN,"
                        + " ipban_wildcard BOOLEAN, active BOOLEAN)",
                "CREATE TABLE litebans_history (id INTEGER PRIMARY KEY AUTOINCREMENT, date TIMESTAMP, name VARCHAR(16), uuid VARCHAR(36), ip VARCHAR(45))");
    }

    @Test
    void vanillaBanListsAreRead() {
        String players = """
                [
                  {"uuid": "11111111-2222-3333-4444-555555555555", "name": "Alice", "created": "2026-10-01 12:00:00 +0300",
                   "source": "Server", "expires": "forever", "reason": "Banned by an operator."},
                  {"uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "name": "Bob", "created": "2026-10-01 12:00:00 +0000",
                   "source": "Moderator", "expires": "2099-01-01 00:00:00 +0000", "reason": ""},
                  {"uuid": "not-a-uuid", "name": "Broken"}
                ]""";
        String ips = """
                [{"ip": "10.0.0.5", "created": "2026-10-02 08:30:00 +0000", "source": "Server", "expires": "forever", "reason": "Proxy"},
                 {"ip": "example.com", "created": "2026-10-02 08:30:00 +0000"}]""";

        List<ImportRecord> records = ImportSources.vanilla(players, ips, DEFAULTS);

        assertEquals(3, records.size(), "invalid UUIDs and hostnames are skipped");
        var alice = records.get(0);
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"), alice.target());
        assertEquals(0, alice.expires());
        assertEquals(java.time.Instant.parse("2026-10-01T09:00:00Z").toEpochMilli(), alice.created());
        assertEquals("Server", alice.actorName());
        assertEquals("No reason given", records.get(1).reason());
        assertEquals(java.time.Instant.parse("2099-01-01T00:00:00Z").toEpochMilli(), records.get(1).expires());
        var ip = records.get(2);
        assertEquals(Kind.IPBAN, ip.kind());
        assertNull(ip.target());
        assertEquals("10.0.0.5", ip.ip());
        assertTrue(records.stream().allMatch(r -> r.state().equals("ACTIVE")));
        assertTrue(ImportSources.vanilla(null, "", DEFAULTS).isEmpty(), "missing files mean nothing to import");
    }

    @Test
    void uuidsWithAndWithoutDashes() {
        assertEquals(UUID.fromString("11111111-2222-3333-4444-555555555555"), ImportSources.uuid("11111111222233334444555555555555"));
        assertNull(ImportSources.uuid("10.0.0.5"));
        assertNull(ImportSources.uuid("#offline#"));
    }
}
