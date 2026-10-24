package com.efxstudios.bans.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC access for SQLite and MySQL. Only called from the {@link Engine} worker thread.
 * Every punishment change is written together with its change-log row in one transaction.
 * All values are bound as parameters; the only concatenated SQL fragments are constants from this class.
 */
public final class Store implements AutoCloseable {
    public record Profile(UUID uuid, String name, String ip, int exemptions) {}
    public record Change(long sequence, Punishment punishment, String action, String actorName, String reason, long at) {}
    public record Snapshot(long cursor, List<Punishment> active) {}
    public record Page(List<Punishment> entries, int number, int pages, long total) {}

    private static final int SCHEMA_VERSION = 1;

    private final HikariDataSource pool;
    private final boolean mysql;

    public Store(DatabaseSettings settings) throws SQLException {
        mysql = settings.mysql();
        HikariConfig config = new HikariConfig();
        config.setPoolName("efxBans");
        config.setMaximumPoolSize(mysql ? settings.poolSize() : 1);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(2000);
        config.setInitializationFailTimeout(10000);
        if (mysql) {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            String host = settings.host().contains(":") ? "[" + settings.host() + "]" : settings.host();
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + settings.port() + "/" + settings.database()
                    + "?sslMode=" + (settings.tls() ? "VERIFY_IDENTITY" : "DISABLED")
                    + "&connectTimeout=5000&socketTimeout=10000&characterEncoding=UTF-8"
                    + "&allowLoadLocalInfile=false&allowUrlInLocalInfile=false&autoDeserialize=false");
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.addDataSourceProperty("cachePrepStmts", "true");
        } else {
            config.setDriverClassName("org.sqlite.JDBC");
            config.setJdbcUrl("jdbc:sqlite:" + settings.sqlite().toAbsolutePath());
            config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        }
        pool = new HikariDataSource(config);
        try {
            migrate();
        } catch (SQLException e) {
            pool.close();
            throw e;
        }
    }

    // ---------------------------------------------------------------- schema

    private void migrate() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            if (!mysql) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
            }
            String id = mysql ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
            String engine = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
            s.execute("CREATE TABLE IF NOT EXISTS efxb_meta (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, gate INTEGER NOT NULL)" + engine);
            s.execute((mysql ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO efxb_meta(id,version,gate) VALUES(1," + SCHEMA_VERSION + ",0)");
            try (ResultSet r = s.executeQuery("SELECT version FROM efxb_meta WHERE id=1")) {
                if (!r.next() || r.getInt(1) != SCHEMA_VERSION)
                    throw new SQLException("Unsupported efxBans database schema; refusing to downgrade automatically.");
            }
            s.execute("CREATE TABLE IF NOT EXISTS efxb_profiles (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, name_lower VARCHAR(64) NOT NULL,"
                    + " ip VARCHAR(45), seen BIGINT NOT NULL, exemptions INTEGER NOT NULL)" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS efxb_names (uuid VARCHAR(36) NOT NULL, name_lower VARCHAR(64) NOT NULL, seen BIGINT NOT NULL,"
                    + " PRIMARY KEY(uuid,name_lower))" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS efxb_ips (uuid VARCHAR(36) NOT NULL, ip VARCHAR(45) NOT NULL, first_seen BIGINT NOT NULL,"
                    + " last_seen BIGINT NOT NULL, PRIMARY KEY(uuid,ip))" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS efxb_punishments (id " + id + ", kind VARCHAR(8) NOT NULL, target_uuid VARCHAR(36),"
                    + " target_name VARCHAR(64) NOT NULL, ip VARCHAR(45), actor VARCHAR(36) NOT NULL, actor_name VARCHAR(64) NOT NULL,"
                    + " reason TEXT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL, state VARCHAR(16) NOT NULL,"
                    + " closed_at BIGINT NOT NULL DEFAULT 0, closed_by VARCHAR(36), closed_name VARCHAR(64), close_reason TEXT)" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS efxb_changes (id " + id + ", punishment_id BIGINT NOT NULL, action VARCHAR(16) NOT NULL,"
                    + " actor_name VARCHAR(64) NOT NULL, reason TEXT NOT NULL, at_time BIGINT NOT NULL)" + engine);
            s.execute("CREATE TABLE IF NOT EXISTS efxb_warning_deliveries (punishment_id BIGINT PRIMARY KEY, target_uuid VARCHAR(36) NOT NULL,"
                    + " delivered_at BIGINT NOT NULL DEFAULT 0)" + engine);
            addSilentColumn(c, s);
            index(c, "efxb_name_idx", "efxb_profiles", "name_lower");
            index(c, "efxb_ip_idx", "efxb_ips", "ip");
            index(c, "efxb_target_idx", "efxb_punishments", "target_uuid,id");
            index(c, "efxb_active_idx", "efxb_punishments", "state,expires");
            index(c, "efxb_banip_idx", "efxb_punishments", "ip,state");
            index(c, "efxb_change_idx", "efxb_changes", "punishment_id,id");
            index(c, "efxb_warning_idx", "efxb_warning_deliveries", "target_uuid,delivered_at");
        }
    }

    /** Added after the first release; older databases are upgraded in place. */
    private static void addSilentColumn(Connection c, Statement s) throws SQLException {
        boolean exists;
        try (ResultSet columns = c.getMetaData().getColumns(c.getCatalog(), null, "efxb_punishments", "silent")) {
            exists = columns.next();
        }
        if (exists) return;
        try {
            s.execute("ALTER TABLE efxb_punishments ADD COLUMN silent INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException e) {
            // Another server sharing the database may have added it at the same moment.
            if (e.getErrorCode() != 1060 && !Objects.requireNonNullElse(e.getMessage(), "").contains("duplicate column")) throw e;
        }
    }

    private static void index(Connection c, String name, String table, String columns) throws SQLException {
        boolean exists = false;
        try (ResultSet r = c.getMetaData().getIndexInfo(c.getCatalog(), null, table, false, false)) {
            while (r.next()) if (name.equalsIgnoreCase(r.getString("INDEX_NAME"))) exists = true;
        }
        if (exists) return;
        try (Statement s = c.createStatement()) {
            s.execute("CREATE INDEX " + name + " ON " + table + "(" + columns + ")");
        } catch (SQLException e) {
            if (e.getErrorCode() != 1061 && !Objects.requireNonNullElse(e.getMessage(), "").contains("already exists")) throw e;
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Locks the meta row, so commits happen in change-log order.
                // AUTO_INCREMENT alone is NOT a safe change-feed cursor under concurrent writers.
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("UPDATE efxb_meta SET gate=gate WHERE id=1");
                }
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ---------------------------------------------------------------- profiles

    /** Records a login: current name, name history and IP history. A null address keeps the stored one. */
    public void remember(UUID uuid, String name, String address, int exemptions) throws SQLException {
        String id = uuid.toString();
        String lower = name.toLowerCase(Locale.ROOT);
        transaction(c -> {
            long now = System.currentTimeMillis();
            execute(c, "UPDATE efxb_profiles SET name=?,name_lower=?,ip=COALESCE(?,ip),seen=?,exemptions=? WHERE uuid=?", name, lower, address, now, exemptions, id);
            if (!exists(c, "SELECT uuid FROM efxb_profiles WHERE uuid=?", id))
                execute(c, "INSERT INTO efxb_profiles(uuid,name,name_lower,ip,seen,exemptions) VALUES(?,?,?,?,?,?)", id, name, lower, address, now, exemptions);
            execute(c, "UPDATE efxb_names SET seen=? WHERE uuid=? AND name_lower=?", now, id, lower);
            if (!exists(c, "SELECT uuid FROM efxb_names WHERE uuid=? AND name_lower=?", id, lower))
                execute(c, "INSERT INTO efxb_names(uuid,name_lower,seen) VALUES(?,?,?)", id, lower, now);
            if (address != null) {
                execute(c, "UPDATE efxb_ips SET last_seen=? WHERE uuid=? AND ip=?", now, id, address);
                if (!exists(c, "SELECT uuid FROM efxb_ips WHERE uuid=? AND ip=?", id, address))
                    execute(c, "INSERT INTO efxb_ips(uuid,ip,first_seen,last_seen) VALUES(?,?,?,?)", id, address, now, now);
            }
            return null;
        });
    }

    /** Finds an account by UUID or current name; a former name is only accepted when it is unambiguous. */
    public Optional<Profile> profile(String nameOrUuid) throws SQLException {
        UUID uuid = null;
        try {
            uuid = UUID.fromString(nameOrUuid);
        } catch (IllegalArgumentException ignored) {
            // Not a UUID: look it up as a name.
        }
        try (Connection c = pool.getConnection()) {
            List<Profile> found;
            if (uuid != null) {
                found = profiles(c, "SELECT * FROM efxb_profiles WHERE uuid=?", uuid.toString());
            } else {
                String lower = nameOrUuid.toLowerCase(Locale.ROOT);
                found = profiles(c, "SELECT * FROM efxb_profiles WHERE name_lower=?", lower);
                if (found.isEmpty())
                    found = profiles(c, "SELECT p.* FROM efxb_profiles p JOIN efxb_names n ON p.uuid=n.uuid WHERE n.name_lower=?", lower);
            }
            if (found.size() > 1) throw new Problem("ambiguous-name");
            return found.stream().findFirst();
        }
    }

    private static List<Profile> profiles(Connection c, String sql, String value) throws SQLException {
        List<Profile> result = new ArrayList<>();
        try (PreparedStatement s = prepare(c, sql, value); ResultSet r = s.executeQuery()) {
            while (r.next()) result.add(new Profile(UUID.fromString(r.getString("uuid")), r.getString("name"), r.getString("ip"), r.getInt("exemptions")));
        }
        return result;
    }

    public List<String> names() throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement s = prepare(c, "SELECT name FROM efxb_profiles ORDER BY seen DESC LIMIT 1000");
             ResultSet r = s.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (r.next()) names.add(r.getString(1));
            return names;
        }
    }

    /** Accounts that ever used one of this account's IP addresses (at most 100). */
    public List<Profile> alts(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return profiles(c, "SELECT DISTINCT p.* FROM efxb_profiles p"
                    + " JOIN efxb_ips other_ip ON p.uuid=other_ip.uuid"
                    + " JOIN efxb_ips own_ip ON other_ip.ip=own_ip.ip"
                    + " WHERE own_ip.uuid=? AND p.uuid<>own_ip.uuid ORDER BY p.seen DESC LIMIT 100", uuid.toString());
        }
    }

    // ---------------------------------------------------------------- punishments

    public Punishment issue(Kind kind, UUID target, String name, String ip, String actor, String actorName, String reason, long duration) throws SQLException {
        return issue(kind, target, name, ip, actor, actorName, reason, duration, false);
    }

    /** Duration 0 means permanent for bans/mutes and is required for kicks/warnings. */
    public Punishment issue(Kind kind, UUID target, String name, String ip, String actor, String actorName, String reason, long duration,
                            boolean silent) throws SQLException {
        if (kind != Kind.IPBAN && target == null) throw new Problem("account-required");
        if (kind == Kind.IPBAN && ip == null) throw new Problem("unknown-ip");
        if (duration < 0 || !kind.timed() && duration != 0) throw new Problem("invalid-duration");
        if (reason.isBlank() || reason.length() > 1000) throw new Problem("invalid-reason");
        return transaction(c -> {
            long now = System.currentTimeMillis();
            long expires = duration == 0 ? 0 : Math.addExact(now, duration);
            if (kind.timed() && hasActive(c, kind, target, ip, now)) throw new Problem("duplicate-active");
            long id;
            try (PreparedStatement s = c.prepareStatement("INSERT INTO efxb_punishments"
                    + "(kind,target_uuid,target_name,ip,actor,actor_name,reason,created,expires,state,silent) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(s, kind.name(), target == null ? null : target.toString(), name, ip, actor, actorName, reason, now, expires,
                        kind.timed() ? "ACTIVE" : "EXECUTED", silent ? 1 : 0);
                s.executeUpdate();
                try (ResultSet r = s.getGeneratedKeys()) {
                    if (!r.next()) throw new SQLException("No punishment ID was generated.");
                    id = r.getLong(1);
                }
            }
            change(c, id, "ISSUE", actorName, reason, now);
            if (kind == Kind.WARN)
                execute(c, "INSERT INTO efxb_warning_deliveries(punishment_id,target_uuid,delivered_at) VALUES(?,?,0)", id, target.toString());
            return get(c, id).orElseThrow();
        });
    }

    /** An account may hold one active punishment per kind; an address-only IP ban is checked per address. */
    private static boolean hasActive(Connection c, Kind kind, UUID target, String ip, long now) throws SQLException {
        String base = "SELECT id FROM efxb_punishments WHERE kind=? AND state='ACTIVE' AND (expires=0 OR expires>?) AND ";
        if (target != null) return exists(c, base + "target_uuid=?", kind.name(), now, target.toString());
        return exists(c, base + "ip=? AND target_uuid IS NULL", kind.name(), now, ip);
    }

    public List<Punishment> remove(Long id, UUID uuid, String ip, Set<Kind> kinds, boolean undo, String actor, String actorName, String reason)
            throws SQLException {
        return remove(id, uuid, ip, kinds, undo, actor, actorName, reason, true);
    }

    /**
     * Closes matching punishments: REMOVED for unban/unmute, REVOKED for undo.
     * A name unban uses target_uuid ONLY; it never expands to other accounts sharing an IP.
     * Without addressAccess, an #ID that points at an account-less IP ban is refused like a direct IP unban.
     */
    public List<Punishment> remove(Long id, UUID uuid, String ip, Set<Kind> kinds, boolean undo, String actor, String actorName, String reason,
                                   boolean addressAccess) throws SQLException {
        if (id == null && uuid == null && ip == null) throw new IllegalArgumentException("A target is required.");
        String selector = id != null ? "id=?" : uuid != null ? "target_uuid=?" : "ip=?";
        Object value = id != null ? id : uuid != null ? uuid.toString() : ip;
        return transaction(c -> {
            List<Punishment> rows = query(c, "SELECT * FROM efxb_punishments WHERE " + selector, value);
            List<Punishment> changed = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Punishment p : rows) {
                if (!kinds.contains(p.kind())) continue;
                if (undo ? p.state().equals("REVOKED") : !p.active(now)) continue;
                if (!addressAccess && p.target() == null) throw new Problem("no-permission", Map.of("permission", "efxbans.unban.address"));
                execute(c, "UPDATE efxb_punishments SET state=?,closed_at=?,closed_by=?,closed_name=?,close_reason=? WHERE id=?",
                        undo ? "REVOKED" : "REMOVED", now, actor, actorName, reason, p.id());
                change(c, p.id(), undo ? "UNDO" : "REMOVE", actorName, reason, now);
                changed.add(get(c, p.id()).orElseThrow());
            }
            return changed;
        });
    }

    private static void change(Connection c, long id, String action, String actor, String reason, long now) throws SQLException {
        execute(c, "INSERT INTO efxb_changes(punishment_id,action,actor_name,reason,at_time) VALUES(?,?,?,?,?)", id, action, actor, reason, now);
    }

    public Optional<Punishment> get(long id) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return get(c, id);
        }
    }

    private static Optional<Punishment> get(Connection c, long id) throws SQLException {
        return query(c, "SELECT * FROM efxb_punishments WHERE id=?", id).stream().findFirst();
    }

    /** Every punishment of an account, newest first, 10 per page. */
    public List<Punishment> history(UUID uuid, int page) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return query(c, "SELECT * FROM efxb_punishments WHERE target_uuid=? ORDER BY id DESC LIMIT 10 OFFSET ?", uuid.toString(), (long) (page - 1) * 10);
        }
    }

    /** Active bans, IP bans and mutes for one ID, account or recorded IP; 5 per page. */
    public Page inquiry(Long id, UUID uuid, String ip, int requested) throws SQLException {
        String selector = id != null ? "id=?" : uuid != null ? "target_uuid=?" : "ip=?";
        Object value = id != null ? id : uuid != null ? uuid.toString() : ip;
        String filter = "kind IN ('BAN','IPBAN','MUTE') AND " + selector + " AND state='ACTIVE' AND (expires=0 OR expires>?)";
        return transaction(c -> {
            long now = System.currentTimeMillis();
            long total = count(c, "SELECT COUNT(*) FROM efxb_punishments WHERE " + filter, value, now);
            int pages = (int) Math.max(1, (total + 4) / 5);
            int page = Math.min(Math.max(1, requested), pages);
            List<Punishment> rows = query(c, "SELECT * FROM efxb_punishments WHERE " + filter + " ORDER BY id DESC LIMIT 5 OFFSET ?", value, now, (long) (page - 1) * 5);
            return new Page(rows, page, pages, total);
        });
    }

    /** Active punishments of the given kinds, newest first, 10 per page. */
    public Page activePage(Set<Kind> kinds, int requested) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(kinds.size(), "?"));
        List<Object> args = new ArrayList<>();
        kinds.stream().sorted().forEach(k -> args.add(k.name()));
        args.add(System.currentTimeMillis());
        String filter = "kind IN (" + placeholders + ") AND state='ACTIVE' AND (expires=0 OR expires>?)";
        return transaction(c -> {
            long total = count(c, "SELECT COUNT(*) FROM efxb_punishments WHERE " + filter, args.toArray());
            int pages = (int) Math.max(1, (total + 9) / 10);
            int page = Math.min(Math.max(1, requested), pages);
            List<Object> paged = new ArrayList<>(args);
            paged.add((long) (page - 1) * 10);
            List<Punishment> rows = query(c, "SELECT * FROM efxb_punishments WHERE " + filter + " ORDER BY id DESC LIMIT 10 OFFSET ?", paged.toArray());
            return new Page(rows, page, pages, total);
        });
    }

    public List<Punishment> pendingWarnings(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return query(c, "SELECT p.* FROM efxb_punishments p JOIN efxb_warning_deliveries d ON p.id=d.punishment_id"
                    + " WHERE d.target_uuid=? AND d.delivered_at=0 AND p.state='EXECUTED' ORDER BY p.id LIMIT 100", uuid.toString());
        }
    }

    public void acknowledgeWarnings(UUID uuid, List<Long> ids) throws SQLException {
        transaction(c -> {
            for (long id : ids)
                execute(c, "UPDATE efxb_warning_deliveries SET delivered_at=? WHERE punishment_id=? AND target_uuid=? AND delivered_at=0",
                        System.currentTimeMillis(), id, uuid.toString());
            return null;
        });
    }

    public List<Change> audit(long id) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return changes(c, "SELECT * FROM efxb_changes WHERE punishment_id=? ORDER BY id LIMIT 100", id);
        }
    }

    /** Login check: active punishments of the account, plus IP bans on the connecting address. */
    public List<Punishment> activeFor(UUID uuid, String ip) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return query(c, "SELECT * FROM efxb_punishments WHERE state='ACTIVE' AND (expires=0 OR expires>?)"
                    + " AND (target_uuid=? OR (kind='IPBAN' AND ip=?)) ORDER BY id DESC", System.currentTimeMillis(), uuid.toString(), ip);
        }
    }

    // ---------------------------------------------------------------- change feed

    public Snapshot snapshot() throws SQLException {
        // Same lock as writers: active rows and cursor form one coherent snapshot.
        return transaction(c -> new Snapshot(cursor(c),
                query(c, "SELECT * FROM efxb_punishments WHERE state='ACTIVE' AND (expires=0 OR expires>?)", System.currentTimeMillis())));
    }

    /** Up to 500 change-log entries after the given cursor, oldest first. */
    public List<Change> changes(long after) throws SQLException {
        try (Connection c = pool.getConnection()) {
            return changes(c, "SELECT * FROM efxb_changes WHERE id>? ORDER BY id LIMIT 500", after);
        }
    }

    private static List<Change> changes(Connection c, String sql, long value) throws SQLException {
        record Row(long id, long punishment, String action, String actor, String reason, long at) {}
        List<Row> rows = new ArrayList<>();
        // Read all events and close the result set before loading punishments (portable JDBC).
        try (PreparedStatement s = prepare(c, sql, value); ResultSet r = s.executeQuery()) {
            while (r.next())
                rows.add(new Row(r.getLong("id"), r.getLong("punishment_id"), r.getString("action"), r.getString("actor_name"), r.getString("reason"), r.getLong("at_time")));
        }
        List<Change> result = new ArrayList<>();
        for (Row row : rows) result.add(new Change(row.id, get(c, row.punishment).orElseThrow(), row.action, row.actor, row.reason, row.at));
        return result;
    }

    private static long cursor(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM efxb_changes")) {
            r.next();
            return r.getLong(1);
        }
    }

    // ---------------------------------------------------------------- JDBC helpers

    private static List<Punishment> query(Connection c, String sql, Object... values) throws SQLException {
        List<Punishment> result = new ArrayList<>();
        try (PreparedStatement s = prepare(c, sql, values); ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String uuid = r.getString("target_uuid");
                result.add(new Punishment(r.getLong("id"), Kind.valueOf(r.getString("kind")), uuid == null ? null : UUID.fromString(uuid),
                        r.getString("target_name"), r.getString("ip"), r.getString("actor"), r.getString("actor_name"), r.getString("reason"),
                        r.getLong("created"), r.getLong("expires"), r.getString("state"), r.getLong("closed_at"), r.getString("closed_by"),
                        r.getString("closed_name"), r.getString("close_reason"), r.getInt("silent") != 0));
            }
        }
        return result;
    }

    private static long count(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, values); ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    private static boolean exists(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, values); ResultSet r = s.executeQuery()) {
            return r.next();
        }
    }

    private static int execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = prepare(c, sql, values)) {
            return s.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement s = c.prepareStatement(sql);
        try {
            bind(s, values);
        } catch (SQLException e) {
            s.close();
            throw e;
        }
        return s;
    }

    private static void bind(PreparedStatement s, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
    }

    @Override
    public void close() {
        pool.close();
    }
}
