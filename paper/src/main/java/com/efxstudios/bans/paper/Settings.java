package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.DatabaseSettings;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Times;
import com.efxstudios.bans.common.Webhook;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

/** Validated, immutable view of config.yml and messages.yml. A reload swaps the whole object at once. */
public record Settings(DatabaseSettings database, long pollMillis, long staleMillis, boolean publicBroadcast,
                       long defaultMax, Map<String, Long> limits, List<String> muteCommands, Set<String> consolePermissions,
                       boolean coreIntegration, Map<String, Preset> presets, Messages messages, Webhook.Settings webhook,
                       Updates updates) {

    public record Preset(String command, List<String> aliases, Kind kind, long duration, String reason, String permission, boolean enabled) {}

    public record Updates(boolean enabled, boolean notifyStaff, int intervalHours) {
        public static final Updates DISABLED = new Updates(false, false, 12);

        public Updates {
            if (intervalHours < 1 || intervalHours > 168) throw new IllegalArgumentException("update-checker.interval-hours 1–168 olmalı");
        }
    }

    private static final Set<String> RESERVED = Set.of("ban", "ipban", "mute", "kick", "warn", "unban", "unmute", "history",
            "punishment", "undo", "alts", "efxbans", "banlist", "mutelist", "sorgu");

    public static Settings load(BansPlugin plugin) throws Exception {
        YamlConfiguration c = read(plugin, "config.yml");
        YamlConfiguration m = read(plugin, "messages.yml");

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
        if (poll < 100 || poll > 60000 || stale < poll * 2 || stale > 300000) throw new IllegalArgumentException("network süreleri geçersiz");

        Map<String, Long> limits = new HashMap<>();
        for (Map<?, ?> row : c.getMapList("permissions.duration-limits")) {
            String permission = Objects.toString(row.get("permission"), "");
            validatePermission(permission);
            limits.put(permission, Times.parse(Objects.toString(row.get("maximum"), "")));
        }

        Messages messages = new Messages(m.getString("prefix", ""), texts(m), m.getString("timezone", "Europe/Istanbul"));

        Webhook.Settings webhook = new Webhook.Settings(
                c.getBoolean("discord.enabled", false),
                c.getString("discord.url", ""),
                Set.copyOf(c.getStringList("discord.actions")),
                c.getBoolean("discord.include-ip", false),
                c.getString("discord.title", "efxBans"),
                c.getString("discord.footer", "efxStudios • Ceza kaydı"));

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
                c.getBoolean("integrations.efxcore", true),
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
            for (String label : labels) if (!claimed.add(label)) throw new IllegalArgumentException("Tekrarlanan/ayrılmış komut adı: " + label);

            Kind kind = Kind.valueOf(c.getString(path + "type", "WARN").toUpperCase(Locale.ROOT));
            String time = c.getString(path + "duration", "permanent");
            long duration = time.equalsIgnoreCase("permanent") ? 0 : Times.parse(time);
            if (!kind.timed() && duration != 0) throw new IllegalArgumentException(command + ": kick/warn süre alamaz");
            String reason = c.getString(path + "reason", "");
            if (reason.isBlank() || reason.length() > 1000) throw new IllegalArgumentException(command + ": sebep 1–1000 karakter olmalı");
            String permission = c.getString(path + "permission", "efxbans.preset." + asciiLabel(command));
            validatePermission(permission);
            presets.put(command, new Preset(command, List.copyOf(aliases), kind, duration, reason, permission, c.getBoolean(path + "enabled", true)));
        }
        return presets;
    }

    /** Every string (or list of lines) in messages.yml, keyed by its dotted path. */
    private static Map<String, String> texts(YamlConfiguration m) {
        Map<String, String> texts = new HashMap<>();
        for (String key : m.getKeys(true)) {
            Object value = m.get(key);
            if (value instanceof List<?> list) texts.put(key, String.join("\n", list.stream().map(Object::toString).toList()));
            else if (value instanceof String text) texts.put(key, text);
        }
        return texts;
    }

    private static void validateLabel(String label) {
        if (!label.matches("[a-zçğıöşü][a-z0-9çğıöşü_-]{0,31}")) throw new IllegalArgumentException("Geçersiz komut adı (küçük harf kullanın): " + label);
    }

    private static String asciiLabel(String label) {
        return label.replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u');
    }

    private static void validatePermission(String permission) {
        if (!permission.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Geçersiz yetki: " + permission);
    }

    /**
     * Loads a file from the data folder, creating it from the JAR on first start.
     * messages.yml additionally receives new keys and updated untouched defaults, with a one-time backup.
     */
    private static YamlConfiguration read(BansPlugin plugin, String file) throws Exception {
        File target = new File(plugin.getDataFolder(), file);
        if (!target.exists()) plugin.saveResource(file, false);
        YamlConfiguration config = new YamlConfiguration();
        config.load(target);
        try (InputStream stream = plugin.getResource(file)) {
            if (stream == null) throw new IOException("Paket kaynağı eksik: " + file);
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (file.equals("messages.yml")) updateMessages(plugin, target.toPath(), config, defaults);
            else config.setDefaults(defaults);
        }
        return config;
    }

    private static void updateMessages(BansPlugin plugin, Path target, YamlConfiguration config, YamlConfiguration defaults) throws IOException {
        boolean updated = false;
        for (String key : defaults.getKeys(true)) if (!defaults.isConfigurationSection(key) && !config.isSet(key)) updated = true;
        // A value still equal to the first release's default was never customized: upgrade it to the new default.
        try (InputStream legacy = plugin.getResource("messages-v1.yml")) {
            if (legacy != null) {
                YamlConfiguration old = YamlConfiguration.loadConfiguration(new InputStreamReader(legacy, StandardCharsets.UTF_8));
                for (String key : old.getKeys(true)) {
                    if (old.isConfigurationSection(key) || !config.isSet(key) || !defaults.contains(key)) continue;
                    if (Objects.equals(config.get(key), old.get(key)) && !Objects.equals(config.get(key), defaults.get(key))) {
                        config.set(key, defaults.get(key));
                        updated = true;
                    }
                }
            }
        }
        config.setDefaults(defaults);
        config.options().copyDefaults(true);
        if (!updated) return;
        Path backup = target.resolveSibling("messages.before-update.yml");
        if (!Files.exists(backup)) Files.copy(target, backup);
        Path temporary = Files.createTempFile(target.getParent(), "messages-", ".tmp");
        try {
            config.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Enabled preset commands and aliases; a change here needs a restart (Brigadier trees are built once). */
    public Map<String, List<String>> topology() {
        Map<String, List<String>> result = new TreeMap<>();
        presets.values().stream().filter(Preset::enabled).forEach(p -> result.put(p.command(), p.aliases()));
        return result;
    }
}
