package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Addresses;
import com.efxstudios.bans.common.ImportRecord;
import com.efxstudios.bans.common.Kind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class ImportSources {

    record Defaults(String noReason, String ipTarget, String console, java.util.function.Function<String, UUID> resolveName, List<String> skipped) {
        Defaults(String noReason, String ipTarget, String console, java.util.function.Function<String, UUID> resolveName) {
            this(noReason, ipTarget, console, resolveName, new ArrayList<>());
        }

        Defaults(String noReason, String ipTarget, String console) {
            this(noReason, ipTarget, console, name -> null);
        }
    }

    private static final DateTimeFormatter VANILLA_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    private ImportSources() {}

    static List<ImportRecord> vanilla(String bannedPlayers, String bannedIps, Defaults defaults) {
        List<ImportRecord> records = new ArrayList<>();
        for (JsonObject entry : objects(bannedPlayers)) {
            UUID uuid = uuid(text(entry, "uuid"));
            if (uuid == null) continue;
            long created = vanillaDate(text(entry, "created"), 0);
            records.add(new ImportRecord("player:" + uuid + ":" + created, Kind.BAN, uuid, Objects.requireNonNullElse(text(entry, "name"), uuid.toString()),
                    null, actor(text(entry, "source"), defaults), reason(text(entry, "reason"), defaults), created, vanillaExpiry(text(entry, "expires")),
                    "ACTIVE", 0, null, false));
        }
        for (JsonObject entry : objects(bannedIps)) {
            String ip = entry.has("ip") ? Addresses.parse(text(entry, "ip")) : null;
            if (ip == null) continue;
            long created = vanillaDate(text(entry, "created"), 0);
            records.add(new ImportRecord("ip:" + ip + ":" + created, Kind.IPBAN, null, defaults.ipTarget(), ip, actor(text(entry, "source"), defaults),
                    reason(text(entry, "reason"), defaults), created, vanillaExpiry(text(entry, "expires")), "ACTIVE", 0, null, false));
        }
        return records;
    }

    private static List<JsonObject> objects(String json) {
        if (json == null || json.isBlank()) return List.of();
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) return List.of();
        List<JsonObject> objects = new ArrayList<>();
        for (JsonElement element : (JsonArray) root) if (element.isJsonObject()) objects.add(element.getAsJsonObject());
        return objects;
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static long vanillaDate(String text, long fallback) {
        if (text == null) return fallback;
        try {
            return ZonedDateTime.parse(text, VANILLA_DATE).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long vanillaExpiry(String text) {
        return text == null || text.equalsIgnoreCase("forever") ? 0 : vanillaDate(text, 0);
    }

    static List<ImportRecord> advancedBan(Connection c, long now, Defaults defaults) throws SQLException {

        Map<String, Long> active = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM Punishments")) {
            while (r.next()) active.put(r.getString("uuid") + "|" + r.getLong("start") + "|" + r.getString("punishmentType"), r.getLong("id"));
        }
        List<ImportRecord> records = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM PunishmentHistory")) {
            while (r.next()) {
                String key = r.getString("uuid") + "|" + r.getLong("start") + "|" + r.getString("punishmentType");
                seen.add(key);
                advancedBanRow(r, "history:" + r.getLong("id"), active.containsKey(key), now, defaults, records);
            }
        }

        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM Punishments")) {
            while (r.next()) {
                String key = r.getString("uuid") + "|" + r.getLong("start") + "|" + r.getString("punishmentType");
                if (!seen.contains(key)) advancedBanRow(r, "active:" + r.getLong("id"), true, now, defaults, records);
            }
        }
        return records;
    }

    private static void advancedBanRow(ResultSet r, String sourceId, boolean isActive, long now, Defaults defaults, List<ImportRecord> out) throws SQLException {
        String type = Objects.requireNonNullElse(r.getString("punishmentType"), "");
        Kind kind = switch (type) {
            case "BAN", "TEMP_BAN" -> Kind.BAN;
            case "IP_BAN", "TEMP_IP_BAN" -> Kind.IPBAN;
            case "MUTE", "TEMP_MUTE" -> Kind.MUTE;
            case "WARNING", "TEMP_WARNING" -> Kind.WARN;
            case "KICK" -> Kind.KICK;
            default -> null;
        };
        if (kind == null) return;
        String id = r.getString("uuid");
        UUID target = kind == Kind.IPBAN ? null : uuid(id);
        if (target == null && kind != Kind.IPBAN && r.getString("name") != null && r.getString("name").matches("[A-Za-z0-9_]{1,16}"))
            target = defaults.resolveName().apply(r.getString("name"));
        String ip = kind == Kind.IPBAN && id != null ? Addresses.parse(id) : null;
        if (kind == Kind.IPBAN ? ip == null : target == null) {
            defaults.skipped().add(sourceId + " (" + r.getString("name") + ")");
            return;
        }
        long start = r.getLong("start");
        long end = r.getLong("end");
        long expires = !kind.timed() || end <= 0 ? 0 : end;
        String state;
        if (!kind.timed()) state = "EXECUTED";
        else if (isActive || (expires > 0 && expires <= now)) state = "ACTIVE";
        else state = "REMOVED";
        String name = Objects.requireNonNullElse(r.getString("name"), target != null ? target.toString() : defaults.ipTarget());
        out.add(new ImportRecord(sourceId, kind, target, name, ip, actor(r.getString("operator"), defaults), reason(r.getString("reason"), defaults),
                start, expires, state, 0, null, false));
    }

    static List<ImportRecord> liteBans(Connection c, String prefix, long now, Defaults defaults) throws SQLException {
        if (!prefix.matches("[A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid LiteBans table_prefix: " + prefix);
        Map<String, String> names = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT uuid, name FROM " + prefix + "history ORDER BY date")) {
            while (r.next()) if (r.getString("uuid") != null && r.getString("name") != null) names.put(r.getString("uuid").toLowerCase(Locale.ROOT), r.getString("name"));
        }
        List<ImportRecord> records = new ArrayList<>();
        for (String table : List.of("bans", "mutes", "warnings", "kicks")) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM " + prefix + table)) {
                Set<String> columns = columns(r);
                while (r.next()) liteBansRow(r, columns, table, names, now, defaults, records);
            }
        }
        return records;
    }

    private static void liteBansRow(ResultSet r, Set<String> columns, String table, Map<String, String> names, long now, Defaults defaults,
                                    List<ImportRecord> out) throws SQLException {
        boolean ipBan = columns.contains("ipban") && r.getBoolean("ipban");
        Kind kind = switch (table) {
            case "bans" -> ipBan ? Kind.IPBAN : Kind.BAN;
            case "mutes" -> Kind.MUTE;
            case "warnings" -> Kind.WARN;
            default -> Kind.KICK;
        };
        String rawUuid = r.getString("uuid");
        UUID target = uuid(rawUuid);
        String ip = columns.contains("ip") ? Addresses.parse(Objects.requireNonNullElse(r.getString("ip"), "")) : null;
        if (kind == Kind.IPBAN ? ip == null && target == null : target == null) {
            defaults.skipped().add(table + ":" + r.getLong("id") + " (" + rawUuid + ")");
            return;
        }
        if (kind == Kind.IPBAN && ip == null) kind = Kind.BAN;
        long time = r.getLong("time");
        long until = columns.contains("until") ? r.getLong("until") : 0;
        long expires = !kind.timed() || until <= 0 ? 0 : until;
        String removedBy = columns.contains("removed_by_name") ? r.getString("removed_by_name") : null;
        boolean active = !columns.contains("active") || r.getBoolean("active");
        String state = "ACTIVE";
        long closedAt = 0;
        String closedName = null;
        if (!kind.timed()) {
            state = "EXECUTED";
        } else if (!active) {
            boolean lifted = removedBy != null && !removedBy.isBlank() && !removedBy.equals("#expired");
            if (lifted || expires == 0 || expires > now) {
                state = "REMOVED";
                closedName = lifted ? removedBy : null;
                Timestamp removedAt = columns.contains("removed_by_date") ? r.getTimestamp("removed_by_date") : null;
                closedAt = removedAt == null ? 0 : removedAt.getTime();
            }
        }
        String name = target != null ? names.getOrDefault(target.toString(), names.getOrDefault(rawUuid.toLowerCase(Locale.ROOT), target.toString()))
                : defaults.ipTarget();
        boolean silent = columns.contains("silent") && r.getBoolean("silent");
        out.add(new ImportRecord(table + ":" + r.getLong("id"), kind, target, name, kind == Kind.IPBAN ? ip : null,
                actor(r.getString("banned_by_name"), defaults), reason(r.getString("reason"), defaults), time, expires, state, closedAt, closedName, silent));
    }

    private static Set<String> columns(ResultSet r) throws SQLException {
        ResultSetMetaData meta = r.getMetaData();
        Set<String> columns = new HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
        return columns;
    }

    static UUID uuid(String text) {
        if (text == null) return null;
        String value = text.strip();
        if (value.matches("[0-9a-fA-F]{32}"))
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        return UUID.fromString(value);
    }

    private static String reason(String reason, Defaults defaults) {
        return reason == null || reason.isBlank() ? defaults.noReason() : reason.strip();
    }

    private static String actor(String name, Defaults defaults) {
        return name == null || name.isBlank() ? defaults.console() : name.strip();
    }
}
