package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.ImportRecord;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Problem;
import com.efxstudios.bans.common.Store;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class Importer {
    static final List<String> SOURCES = List.of("vanilla", "litebans", "advancedban");
    private static final int BATCH = 500;

    private final BansPlugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();

    Importer(BansPlugin plugin) {
        this.plugin = plugin;
    }

    void run(CommandSender sender, String source, boolean confirm) {
        String name = source.toLowerCase(Locale.ROOT);
        if (!SOURCES.contains(name)) throw new Problem("import-unknown-source");
        if (plugin.engine() == null) throw new Problem("import-no-database");
        if (!running.compareAndSet(false, true)) throw new Problem("import-busy");
        plugin.tell(sender, "import.reading", Map.of("source", name));

        plugin.scheduler().async(() -> {
            try {
                ImportSources.Defaults defaults = defaults();
                List<ImportRecord> records = read(name, defaults);
                if (!defaults.skipped().isEmpty())
                    plugin.getLogger().warning(name + ": " + defaults.skipped().size() + " records were left out because they name no known player or address: "
                            + String.join(", ", defaults.skipped().subList(0, Math.min(20, defaults.skipped().size()))) + (defaults.skipped().size() > 20 ? ", ..." : ""));
                if (!confirm) preview(sender, name, records);
                else store(sender, name, records, 0, new Store.ImportResult(0, 0, 0));
            } catch (Exception e) {
                running.set(false);
                failed(sender, name, e);
            }
        });
    }

    private void preview(CommandSender sender, String source, List<ImportRecord> records) {
        long now = System.currentTimeMillis();
        List<String> ids = records.stream().map(ImportRecord::sourceId).toList();
        plugin.engine().call(store -> store.importedCount(source, ids)).whenComplete((already, error) -> plugin.sender(sender, () -> {
            running.set(false);
            if (error != null) {
                failed(sender, source, error);
                return;
            }
            Map<String, String> values = Map.of("source", source, "total", String.valueOf(records.size()),
                    "bans", count(records, Kind.BAN, Kind.IPBAN), "mutes", count(records, Kind.MUTE), "warnings", count(records, Kind.WARN),
                    "kicks", count(records, Kind.KICK), "active", String.valueOf(records.stream().filter(r -> r.active(now)).count()),
                    "already", String.valueOf(already));
            plugin.tell(sender, "import.preview", values);
            if (records.size() > already) plugin.tell(sender, "import.confirm-hint", Map.of("source", source));
        }));
    }

    private void store(CommandSender sender, String source, List<ImportRecord> records, int from, Store.ImportResult total) {
        if (from >= records.size()) {
            running.set(false);
            plugin.sender(sender, () -> plugin.tell(sender, "import.done", Map.of("source", source, "imported", String.valueOf(total.imported()),
                    "already", String.valueOf(total.already()), "conflicts", String.valueOf(total.conflicts()))));
            plugin.getLogger().info("Import from " + source + ": " + total.imported() + " imported, " + total.already() + " already imported, "
                    + total.conflicts() + " skipped (active punishment exists).");
            return;
        }
        List<ImportRecord> batch = records.subList(from, Math.min(records.size(), from + BATCH));
        CompletableFuture<Store.ImportResult> call = plugin.engine().call(store -> store.importBatch(source, batch));
        call.whenComplete((result, error) -> {
            if (error != null) {
                running.set(false);
                plugin.sender(sender, () -> failed(sender, source, error));
                return;
            }
            store(sender, source, records, from + BATCH, total.plus(result));
        });
    }

    private void failed(CommandSender sender, String source, Throwable error) {
        Throwable cause = error.getCause() != null && !(error instanceof Problem) ? error.getCause() : error;
        if (cause instanceof Problem problem) {
            plugin.sender(sender, () -> plugin.tell(sender, problem.key(), problem.values()));
            return;
        }
        String message = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        plugin.getLogger().warning("Import from " + source + " failed: " + message);
        plugin.sender(sender, () -> plugin.tell(sender, "import.failed", Map.of("source", source, "error", message)));
    }

    private static String count(List<ImportRecord> records, Kind... kinds) {
        List<Kind> wanted = List.of(kinds);
        return String.valueOf(records.stream().filter(r -> wanted.contains(r.kind())).count());
    }

    private ImportSources.Defaults defaults() {
        Messages messages = plugin.settings().messages();
        return new ImportSources.Defaults(messages.text("texts.no-reason", "No reason given"), messages.text("texts.ip-target", "IP target"), "CONSOLE",
                Importer::resolveName);
    }

    private List<ImportRecord> read(String source, ImportSources.Defaults defaults) throws Exception {
        long now = System.currentTimeMillis();
        return switch (source) {

            case "vanilla" -> ImportSources.vanilla(readIfExists(Path.of("banned-players.json")), readIfExists(Path.of("banned-ips.json")), defaults);
            case "advancedban" -> {
                try (Connection c = advancedBan()) {
                    yield ImportSources.advancedBan(c, now, defaults);
                }
            }
            default -> {
                YamlConfiguration config = otherConfig("LiteBans");
                try (Connection c = liteBans(config)) {
                    yield ImportSources.liteBans(c, config.getString("sql.table_prefix", "litebans_"), now, defaults);
                }
            }
        };
    }

    static UUID resolveName(String name) {
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached.getUniqueId();
        if (!Bukkit.getOnlineMode()) return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return null;
    }

    private static String readIfExists(Path file) throws Exception {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
    }

    private static YamlConfiguration otherConfig(String pluginName) {
        File file = new File(new File(pluginDirectory(), pluginName), "config.yml");
        if (!file.exists()) throw new Problem("import-not-found", Map.of("file", "plugins/" + pluginName + "/config.yml"));
        return YamlConfiguration.loadConfiguration(file);
    }

    private static File pluginDirectory() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("efxBans");
        return plugin == null ? new File("plugins") : plugin.getDataFolder().getParentFile();
    }

    private static Connection liteBans(YamlConfiguration config) throws Exception {
        String driver = config.getString("sql.driver", "H2");
        if (!driver.equalsIgnoreCase("mysql") && !driver.equalsIgnoreCase("mariadb"))
            throw new Problem("import-unsupported-storage", Map.of("source", "LiteBans", "storage", driver));
        String address = config.getString("sql.address", "localhost:3306");
        String database = config.getString("sql.database", "litebans");
        if (!address.matches("[A-Za-z0-9.:\\[\\]-]+") || !database.matches("[A-Za-z0-9_$-]+"))
            throw new IllegalArgumentException("Invalid LiteBans sql.address or sql.database");
        return DriverManager.getConnection("jdbc:mysql://" + address + "/" + database + "?connectTimeout=5000&socketTimeout=60000",
                config.getString("sql.username", ""), config.getString("sql.password", ""));
    }

    private static Connection advancedBan() throws Exception {
        YamlConfiguration config = otherConfig("AdvancedBan");
        if (config.getBoolean("UseMySQL", false)) {
            String host = config.getString("MySQL.IP", "localhost");
            String database = config.getString("MySQL.DB-Name", "");
            if (!host.matches("[A-Za-z0-9.:\\[\\]-]+") || !database.matches("[A-Za-z0-9_$-]+"))
                throw new IllegalArgumentException("Invalid AdvancedBan MySQL.IP or MySQL.DB-Name");
            return DriverManager.getConnection("jdbc:mysql://" + host + ":" + config.getInt("MySQL.Port", 3306) + "/" + database
                    + "?connectTimeout=5000&socketTimeout=60000", config.getString("MySQL.Username", ""), config.getString("MySQL.Password", ""));
        }
        Plugin advancedBan = Bukkit.getPluginManager().getPlugin("AdvancedBan");
        if (advancedBan == null || !advancedBan.isEnabled()) throw new Problem("import-plugin-required", Map.of("plugin", "AdvancedBan"));

        String url = "jdbc:hsqldb:file:" + advancedBan.getDataFolder().getPath() + "/data/storage;hsqldb.lock_file=false";
        Driver driver = hsqldbDriver(advancedBan.getClass().getClassLoader());
        Properties login = new Properties();
        login.setProperty("user", "SA");
        login.setProperty("password", "");
        Connection connection = driver.connect(url, login);
        if (connection == null) throw new IllegalStateException("AdvancedBan's HSQLDB driver refused " + url);
        return connection;
    }

    private static Driver hsqldbDriver(ClassLoader loader) throws Exception {
        for (String name : List.of("org.hsqldb.jdbc.JDBCDriver", "me.leoko.advancedban.shaded.org.hsqldb.jdbc.JDBCDriver")) {
            try {
                return (Driver) Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException ignored) {

            }
        }
        throw new Problem("import-plugin-required", Map.of("plugin", "AdvancedBan"));
    }
}
