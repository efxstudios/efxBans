package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.DatabaseSettings;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Times;
import com.efxstudios.bans.common.Webhook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Validated, immutable view of config.yml and the selected lang/ file. A reload swaps the whole object at once. */
public record Settings(DatabaseSettings database, long pollMillis, long staleMillis, boolean publicBroadcast,
                       long defaultMax, Map<String, Long> limits, List<String> muteCommands, Set<String> consolePermissions,
                       Map<String, Preset> presets, Messages messages, Webhook.Settings webhook,
                       Updates updates) {

    public record Preset(String command, List<String> aliases, Kind kind, long duration, String reason, String permission, boolean enabled) {}

    public record Updates(boolean enabled, boolean notifyStaff, int intervalHours) {
        public static final Updates DISABLED = new Updates(false, false, 12);

        public Updates {
            if (intervalHours < 1 || intervalHours > 168) throw new IllegalArgumentException("update-checker.interval-hours must be between 1 and 168");
        }
    }

    private static final Set<String> RESERVED = Set.of("ban", "ipban", "mute", "kick", "warn", "unban", "unmute", "history",
            "punishment", "undo", "alts", "efxbans", "banlist", "mutelist", "lookup", "sorgu");

    /** config.yml layout this build writes. Raise it, and add a Step, whenever keys are added, renamed or removed. */
    static final int CONFIG_VERSION = 3;

    /** Sections that belong to the user: the packaged examples are never merged into them. */
    private static final Set<String> USER_OWNED = Set.of("presets");

    public static Settings load(BansPlugin plugin) throws Exception {
        YamlConfiguration c = config(plugin);
        Map<String, String> texts = Languages.load(plugin, c.getString("language", Languages.DEFAULT));

        DatabaseSettings db = new DatabaseSettings(
                c.getString("database.type", "sqlite"),
                plugin.getDataFolder().toPath().resolve("punishments.db"),
                c.getString("database.mysql.host", "localhost"),
                c.getInt("database.mysql.port", 3306),
                c.getString("database.mysql.database", "efxbans"),
                c.getString("database.mysql.username", "efxbans"),
                c.getString("database.mysql.password", ""),
                c.getBoolean("database.mysql.tls", true),
                c.getInt("database.mysql.pool-size", 4));

        long poll = c.getLong("network.poll-millis", 500);
        long stale = c.getLong("network.fail-closed-after-millis", 15000);
        if (poll < 100 || poll > 60000 || stale < poll * 2 || stale > 300000) throw new IllegalArgumentException("Invalid network timings");

        Map<String, Long> limits = new HashMap<>();
        for (Map<?, ?> row : c.getMapList("permissions.duration-limits")) {
            String permission = Objects.toString(row.get("permission"), "");
            validatePermission(permission);
            limits.put(permission, Times.parse(Objects.toString(row.get("maximum"), "")));
        }

        Messages messages = new Messages(texts.getOrDefault("prefix", ""), texts, c.getString("timezone", "UTC"));

        Webhook.Settings webhook = new Webhook.Settings(
                c.getBoolean("discord.enabled", false),
                c.getString("discord.url", ""),
                Set.copyOf(c.getStringList("discord.actions")),
                c.getBoolean("discord.include-ip", false),
                c.getString("discord.title", "efxBans"),
                c.getString("discord.footer", "efxBans • Punishment log"));

        Updates updates = new Updates(
                c.getBoolean("update-checker.enabled", true),
                c.getBoolean("update-checker.notify-staff", true),
                c.getInt("update-checker.interval-hours", 12));

        List<String> muteCommands = c.getStringList("mute.blocked-commands").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();

        return new Settings(db, poll, stale,
                c.getBoolean("notifications.public", false),
                Times.parse(c.getString("permissions.default-maximum", "30d")),
                Map.copyOf(limits),
                muteCommands,
                Set.copyOf(c.getStringList("permissions.console-allowed")),
                Map.copyOf(presets(c)),
                messages, webhook, updates);
    }

    private static Map<String, Preset> presets(YamlConfiguration c) {
        Map<String, Preset> presets = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>(RESERVED);
        // `presets: {}` or a missing section means no presets; packaged examples are never merged back.
        ConfigurationSection section = c.isSet("presets") ? c.getConfigurationSection("presets") : null;
        if (section == null) return presets;
        for (String id : section.getKeys(false)) {
            String path = "presets." + id + ".";
            String command = c.getString(path + "command", id);
            validateLabel(command);
            List<String> aliases = c.getStringList(path + "aliases");
            aliases.forEach(Settings::validateLabel);
            List<String> labels = new ArrayList<>(aliases);
            labels.add(command);
            for (String label : labels) if (!claimed.add(label)) throw new IllegalArgumentException("Duplicate or reserved command name: " + label);

            Kind kind = Kind.valueOf(c.getString(path + "type", "WARN").toUpperCase(Locale.ROOT));
            String time = c.getString(path + "duration", "permanent");
            long duration = time.equalsIgnoreCase("permanent") ? 0 : Times.parse(time);
            if (!kind.timed() && duration != 0) throw new IllegalArgumentException(command + ": kick and warn take no duration");
            String reason = c.getString(path + "reason", "");
            if (reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException(command + ": the reason must be 1–1000 characters");
            String permission = c.getString(path + "permission", "efxbans.preset." + asciiLabel(command));
            validatePermission(permission);
            presets.put(command, new Preset(command, List.copyOf(aliases), kind, duration, reason, permission, c.getBoolean(path + "enabled", true)));
        }
        return presets;
    }

    private static void validateLabel(String label) {
        if (!label.matches("[a-zçğıöşü][a-z0-9çğıöşü_-]{0,31}")) throw new IllegalArgumentException("Invalid command name (use lowercase): " + label);
    }

    private static String asciiLabel(String label) {
        return label.replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u');
    }

    private static void validatePermission(String permission) {
        if (!permission.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Invalid permission: " + permission);
    }

    /** Loads config.yml, creating it on first start and migrating it from older layouts. */
    private static YamlConfiguration config(BansPlugin plugin) throws Exception {
        Path data = plugin.getDataFolder().toPath();
        Files.createDirectories(data);
        Path file = data.resolve("config.yml");
        InputStream bundled = plugin.getResource("config.yml");
        if (bundled == null) throw new IOException("Bundled resource missing: config.yml");
        String template = ConfigFile.read(bundled);
        if (!Files.exists(file)) Files.writeString(file, template, StandardCharsets.UTF_8);
        // A config.yml without config-version is the efxBans 1.0 layout.
        return ConfigFile.load(file, template, 1, steps(data), USER_OWNED, data.resolve("backups"), plugin.getLogger());
    }

    /**
     * config.yml migrations. Each step upgrades version N to N + 1; keys that are only new need no step,
     * because the rebuilt file takes them (with their comments) from the bundled config.yml.
     */
    static List<ConfigFile.Step> steps(Path data) {
        return List.of(
                new ConfigFile.Step(1, "language and timezone settings added; /teammsg and /tm are blocked while muted", c -> {
                    ConfigFile.addToList(c, "mute.blocked-commands", "teammsg", "tm");
                    // 1.0 had only Turkish messages, so existing servers keep Turkish.
                    if (!c.isSet("language")) c.set("language", "tr");
                    if (!c.isSet("timezone")) c.set("timezone", legacyTimezone(data));
                }),
                new ConfigFile.Step(2, "removed branch-specific integration settings", c -> c.set("integrations", null)));
    }

    /** The timezone used to live in messages.yml. */
    private static String legacyTimezone(Path data) {
        Path messages = data.resolve("messages.yml");
        if (!Files.exists(messages)) return "Europe/Istanbul";
        return YamlConfiguration.loadConfiguration(messages.toFile()).getString("timezone", "Europe/Istanbul");
    }

    /** Enabled preset commands and aliases; a change here needs a restart (Brigadier trees are built once). */
    public Map<String, List<String>> topology() {
        Map<String, List<String>> result = new TreeMap<>();
        presets.values().stream().filter(Preset::enabled).forEach(p -> result.put(p.command(), p.aliases()));
        return result;
    }
}
