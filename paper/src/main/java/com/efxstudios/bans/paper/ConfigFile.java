package com.efxstudios.bans.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * A versioned YAML file in the data folder.
 *
 * <p>Every file starts with {@code config-version}. When the file on disk is older than the version this
 * build expects, the file is backed up to {@code backups/}, the migration steps between the two versions
 * run in order (renames, removals, value changes), and the file is rebuilt from the bundled template:
 * new keys and comments come from the template, existing values are kept. A file from a newer build is
 * refused rather than silently downgraded.
 */
final class ConfigFile {
    static final String VERSION_KEY = "config-version";

    /** Upgrades a file from version {@code from} to {@code from + 1}. */
    record Step(int from, String description, Consumer<YamlConfiguration> apply) {}

    private ConfigFile() {}

    /**
     * Loads {@code file}, migrating it first if needed.
     *
     * @param template     the bundled default for this file (its config-version is the target version)
     * @param legacy       the version assumed when the file has no config-version key
     * @param steps        migration steps, any order
     * @param userOwned    sections owned entirely by the user (e.g. presets): copied as a whole, never
     *                     mixed with the template's examples, and left out when the user removed them
     */
    static YamlConfiguration load(Path file, String template, int legacy, List<Step> steps, Set<String> userOwned, Path backups, Logger logger)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = parse(template);
        int target = defaults.getInt(VERSION_KEY, 0);
        YamlConfiguration config = new YamlConfiguration();
        config.load(file.toFile());
        int version = config.isSet(VERSION_KEY) ? config.getInt(VERSION_KEY) : legacy;
        if (version > target)
            throw new IllegalStateException(file.getFileName() + " has config-version " + version + ", but this efxBans build only knows "
                    + target + ". It was written by a newer version; restore a backup or update efxBans.");
        if (version < target) {
            Path backup = backup(file, version, backups);
            for (int v = version; v < target; v++) {
                for (Step step : steps) {
                    if (step.from() != v) continue;
                    step.apply().accept(config);
                    logger.info(file.getFileName() + ": " + step.description());
                }
            }
            config = rebuild(config, template, userOwned);
            config.set(VERSION_KEY, target);
            save(config, file);
            logger.info(file.getFileName() + " upgraded from config-version " + version + " to " + target + ". Backup: " + backup.getFileName());
        }
        config.setDefaults(defaults);
        return config;
    }

    /** The template with every value the user had copied over it. */
    static YamlConfiguration rebuild(YamlConfiguration user, String template, Set<String> userOwned) throws InvalidConfigurationException {
        YamlConfiguration result = parse(template);
        for (String root : userOwned) {
            result.set(root, null);
            if (user.isConfigurationSection(root)) result.createSection(root, toMap(user.getConfigurationSection(root)));
            else if (user.isSet(root)) result.set(root, user.get(root));
        }
        for (String key : user.getKeys(true)) {
            if (user.isConfigurationSection(key) || isOwned(key, userOwned)) continue;
            result.set(key, user.get(key));
        }
        return result;
    }

    private static boolean isOwned(String key, Set<String> userOwned) {
        for (String root : userOwned) if (key.equals(root) || key.startsWith(root + ".")) return true;
        return false;
    }

    private static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }

    static YamlConfiguration parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    static String read(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies the file to backups/name-v3-20261024-031500.yml. */
    static Path backup(Path file, int version, Path backups) throws IOException {
        Files.createDirectories(backups);
        String name = file.getFileName().toString().replaceFirst("\\.yml$", "");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = backups.resolve(name + "-v" + version + "-" + stamp + ".yml");
        for (int i = 2; Files.exists(backup); i++) backup = backups.resolve(name + "-v" + version + "-" + stamp + "-" + i + ".yml");
        Files.copy(file, backup);
        return backup;
    }

    /** Writes through a temporary file so a crash never leaves a half-written config. */
    static void save(YamlConfiguration config, Path file) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // ---------------------------------------------------------------- helpers for migration steps

    /** Moves a value to a new key, unless the new key already has one. */
    static void rename(YamlConfiguration config, String from, String to) {
        if (!config.isSet(from)) return;
        if (!config.isSet(to)) config.set(to, config.get(from));
        config.set(from, null);
    }

    /** Adds values to a string list if they are missing. */
    static void addToList(YamlConfiguration config, String key, String... values) {
        if (!config.isList(key)) return;
        List<String> list = new java.util.ArrayList<>(config.getStringList(key));
        for (String value : values) if (!list.contains(value)) list.add(value);
        config.set(key, list);
    }
}
