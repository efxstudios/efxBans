package com.efxstudios.bans.paper;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Message files in {@code plugins/efxBans/lang/<code>.yml}, selected with {@code language} in config.yml.
 *
 * <p>The bundled languages are copied into lang/ on first start so they can be edited. Any other file,
 * e.g. lang/pt_br.yml, can be added by hand. A key missing from the selected file falls back to the
 * bundled file of that language, then to English, so an incomplete translation never shows an error.
 */
final class Languages {
    static final List<String> BUNDLED = List.of("en", "tr", "de", "es", "fr");
    static final String DEFAULT = "en";
    /** Also keeps the value usable as a file name: no path separators, no "..". */
    private static final Pattern CODE = Pattern.compile("[a-z]{2,3}(?:_[a-z0-9]{2,8})?");

    /** Language-file migrations: config-version N to N + 1. Version 1 is the first lang/ layout. */
    static final List<ConfigFile.Step> STEPS = List.of();

    private Languages() {}

    /** All message texts of a language, keyed by their dotted path. */
    static Map<String, String> load(BansPlugin plugin, String language) throws Exception {
        String code = language.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("Invalid language '" + language + "'. Use a file name from lang/, for example en.");
        Path data = plugin.getDataFolder().toPath();
        Path folder = data.resolve("lang");
        Path backups = data.resolve("backups");
        Logger logger = plugin.getLogger();
        Files.createDirectories(folder);
        migrateLegacyMessages(plugin, data, folder, backups, logger);
        for (String bundled : BUNDLED) {
            Path file = folder.resolve(bundled + ".yml");
            if (!Files.exists(file)) Files.writeString(file, bundled(plugin, bundled), StandardCharsets.UTF_8);
        }

        Path file = folder.resolve(code + ".yml");
        if (!Files.exists(file))
            throw new IllegalArgumentException("lang/" + code + ".yml does not exist. Bundled languages: " + String.join(", ", BUNDLED) + ".");
        boolean bundled = BUNDLED.contains(code);
        String template = bundled(plugin, bundled ? code : DEFAULT);
        YamlConfiguration selected = ConfigFile.load(file, template, 0, STEPS, Set.of(), backups, logger);

        Map<String, String> texts = new HashMap<>(texts(ConfigFile.parse(bundled(plugin, DEFAULT))));
        if (bundled) texts.putAll(texts(ConfigFile.parse(template)));
        texts.putAll(texts(selected));
        return texts;
    }

    /**
     * efxBans 1.0 kept Turkish messages in plugins/efxBans/messages.yml. They become lang/tr.yml with all
     * customizations; config migration sets language: tr for those servers. The old file goes to backups/.
     */
    private static void migrateLegacyMessages(BansPlugin plugin, Path data, Path folder, Path backups, Logger logger) throws Exception {
        Path legacy = data.resolve("messages.yml");
        if (!Files.exists(legacy)) return;
        Path target = folder.resolve("tr.yml");
        if (!Files.exists(target)) {
            YamlConfiguration old = new YamlConfiguration();
            old.load(legacy.toFile());
            String template = bundled(plugin, "tr");
            upgradeUntouchedDefaults(plugin, old, ConfigFile.parse(template));
            old.set("timezone", null); // Now config.yml timezone.
            YamlConfiguration rebuilt = ConfigFile.rebuild(old, template, Set.of());
            rebuilt.set(ConfigFile.VERSION_KEY, ConfigFile.parse(template).getInt(ConfigFile.VERSION_KEY));
            ConfigFile.save(rebuilt, target);
            logger.info("messages.yml was moved to lang/tr.yml with your changes kept.");
        }
        Path backup = ConfigFile.backup(legacy, 0, backups);
        Files.delete(legacy);
        logger.info("The old messages.yml is kept as backups/" + backup.getFileName() + ".");
    }

    /** A value still equal to the very first release's default was never customized: use the current default. */
    private static void upgradeUntouchedDefaults(BansPlugin plugin, YamlConfiguration config, YamlConfiguration defaults) throws Exception {
        InputStream stream = plugin.getResource("messages-v1.yml");
        if (stream == null) return;
        YamlConfiguration first = ConfigFile.parse(ConfigFile.read(stream));
        for (String key : first.getKeys(true)) {
            if (first.isConfigurationSection(key) || !config.isSet(key) || !defaults.contains(key)) continue;
            if (Objects.equals(config.get(key), first.get(key))) config.set(key, defaults.get(key));
        }
    }

    static String bundled(BansPlugin plugin, String code) throws IOException {
        InputStream stream = plugin.getResource("lang/" + code + ".yml");
        if (stream == null) throw new IOException("Bundled resource missing: lang/" + code + ".yml");
        return ConfigFile.read(stream);
    }

    /** Every string (or list of lines, joined with newlines) keyed by its dotted path. */
    static Map<String, String> texts(YamlConfiguration yaml) {
        Map<String, String> texts = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            Object value = yaml.get(key);
            if (value instanceof List<?> list) texts.put(key, String.join("\n", list.stream().map(Object::toString).toList()));
            else if (value instanceof String text) texts.put(key, text);
        }
        return texts;
    }
}
