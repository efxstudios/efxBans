package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** config.yml as efxBans 1.0 shipped it (no config-version, no language). */
    private static final String CONFIG_1_0 = """
            database:
              type: sqlite
            permissions:
              default-maximum: 30d
            mute:
              blocked-commands: [msg, tell, r]
            discord:
              enabled: false
              url: ''
              footer: 'Benim sunucum'
            presets:
              hile:
                command: hile
                type: IPBAN
                duration: 30d
                reason: Hile Kullanımı
            """;

    @TempDir
    Path directory;
    BansPlugin plugin;

    @BeforeEach
    void setup() {
        plugin = mock(BansPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("efxBans-test"));
        when(plugin.getResource(anyString())).thenAnswer(call -> resource(call.getArgument(0)));
    }

    private InputStream resource(String name) {
        return getClass().getClassLoader().getResourceAsStream(name);
    }

    private String bundled(String name) throws Exception {
        return new String(resource(name).readAllBytes(), StandardCharsets.UTF_8);
    }

    /** A current-layout config.yml with only the given extra lines. */
    private void config(String yaml) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "config-version: 2\n" + yaml);
    }

    private static String render(Messages messages, String key, Map<String, String> values) {
        return PLAIN.serialize(messages.render(key, values));
    }

    // ---------------------------------------------------------------- languages

    @Test
    void freshInstallUsesEnglishAndCopiesEveryBundledLanguage() throws Exception {
        Settings settings = Settings.load(plugin);

        for (String code : Languages.BUNDLED) assertTrue(Files.exists(directory.resolve("lang/" + code + ".yml")), code);
        YamlConfiguration written = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(Settings.CONFIG_VERSION, written.getInt("config-version"));
        assertEquals("en", written.getString("language"));
        assertTrue(render(settings.messages(), "confirmation.ban", Map.of("player", "Alice")).contains("Alice was banned."));
        assertEquals("2 hours", settings.messages().duration(7_200_000));
        assertEquals(Kind.IPBAN, settings.presets().get("cheating").kind());
        assertTrue(settings.muteCommands().containsAll(List.of("teammsg", "tm")));
        assertEquals(new Settings.Updates(true, true, 12), settings.updates());
        assertFalse(Files.exists(directory.resolve("backups")));
    }

    @Test
    void languageSettingSelectsTheFile() throws Exception {
        config("language: de\n");
        assertTrue(render(Settings.load(plugin).messages(), "confirmation.ban", Map.of("player", "Alice")).contains("Alice wurde gebannt."));
        config("language: tr\n");
        var messages = Settings.load(plugin).messages();
        assertTrue(render(messages, "confirmation.ban", Map.of("player", "Alice")).contains("Alice yasaklandı."));
        assertEquals("2 saat", messages.duration(7_200_000));
    }

    @Test
    void everyBundledLanguageHasEveryEnglishKey() throws Exception {
        var english = Languages.texts(ConfigFile.parse(bundled("lang/en.yml"))).keySet();
        for (String code : Languages.BUNDLED) {
            var keys = Languages.texts(ConfigFile.parse(bundled("lang/" + code + ".yml"))).keySet();
            for (String key : english) assertTrue(keys.contains(key), code + " is missing " + key);
            for (String key : keys) assertTrue(english.contains(key), code + " has an extra key " + key);
            // Every template must parse, with the same placeholders as English.
            new Messages("", Languages.texts(ConfigFile.parse(bundled("lang/" + code + ".yml"))), "UTC");
        }
    }

    @Test
    void customLanguageFallsBackToEnglishForMissingKeys() throws Exception {
        Files.createDirectories(directory.resolve("lang"));
        Files.writeString(directory.resolve("lang/pt_br.yml"), "config-version: 1\nconfirmation:\n  ban: '{player} foi banido.'\n");
        config("language: pt_br\n");

        var messages = Settings.load(plugin).messages();

        assertEquals("Alice foi banido.", render(messages, "confirmation.ban", Map.of("player", "Alice")));
        assertTrue(render(messages, "confirmation.mute", Map.of("player", "Alice")).contains("Alice was muted."));
    }

    @Test
    void languageMustBeAnExistingFileNameInLangFolder() throws Exception {
        for (String bad : new String[]{"../config", "en/../../x", "EN.yml", ""}) {
            config("language: '" + bad + "'\n");
            assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin), bad);
        }
        config("language: xx\n");
        var error = assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin));
        assertTrue(error.getMessage().contains("lang/xx.yml"));
    }

    // ---------------------------------------------------------------- migration

    @Test
    void altsMigrationReplacesOldDefaultAndKeepsCustomLayout() throws Exception {
        config("language: en\n");
        Path lang = directory.resolve("lang");
        Files.createDirectories(lang);
        Files.writeString(lang.resolve("en.yml"), "config-version: 1\nalts-entry: '<#FF5656>{player}'\n");
        assertEquals("{player}", Settings.load(plugin).messages().text("alts-entry", ""));
        Files.writeString(lang.resolve("en.yml"), "config-version: 1\nalts-entry: 'Account: {player}'\n");
        assertEquals("Account: {player}", Settings.load(plugin).messages().text("alts-entry", ""));
    }

    @Test
    void version1ConfigAndMessagesAreUpgradedWithoutLosingCustomizations() throws Exception {
        Files.writeString(directory.resolve("config.yml"), CONFIG_1_0);
        var legacy = new YamlConfiguration();
        legacy.loadFromString(bundled("messages-v1.yml"));
        legacy.set("broadcast.ban", "Özel duyuru {player}");
        legacy.set("timezone", "Europe/Berlin");
        legacy.save(directory.resolve("messages.yml").toFile());

        Settings settings = Settings.load(plugin);

        // config.yml: version raised, values kept, new keys added with their comments, user presets kept as they were.
        String text = Files.readString(directory.resolve("config.yml"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(Settings.CONFIG_VERSION, config.getInt("config-version"));
        assertEquals("tr", config.getString("language"));
        assertEquals("Europe/Berlin", config.getString("timezone"));
        assertEquals(List.of("msg", "tell", "r", "teammsg", "tm"), config.getStringList("mute.blocked-commands"));
        assertEquals("Benim sunucum", config.getString("discord.footer"));
        assertTrue(config.isSet("update-checker.enabled"));
        assertTrue(text.contains("# Checks GitHub Releases"), "comments of new keys come from the template");
        assertEquals(List.of("hile"), List.copyOf(config.getConfigurationSection("presets").getKeys(false)));
        assertEquals(List.of("hile"), List.copyOf(settings.presets().keySet()));

        // messages.yml became lang/tr.yml: customized values kept, untouched first-release values updated.
        assertFalse(Files.exists(directory.resolve("messages.yml")));
        var messages = settings.messages();
        assertEquals("Özel duyuru Alice", render(messages, "broadcast.ban", Map.of("player", "Alice")));
        assertTrue(render(messages, "inquiry.entry", Map.of()).contains("Veriliş"));
        assertTrue(render(messages, "update.available", Map.of("latest", "9.9.9")).contains("9.9.9"));
        assertNull(YamlConfiguration.loadConfiguration(directory.resolve("lang/tr.yml").toFile()).get("timezone"));

        // Both old files are in backups/.
        try (Stream<Path> backups = Files.list(directory.resolve("backups"))) {
            List<String> names = backups.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.startsWith("config-v1-")), names.toString());
            assertTrue(names.stream().anyMatch(n -> n.startsWith("messages-v0-")), names.toString());
        }

        // A second start changes nothing.
        String after = Files.readString(directory.resolve("config.yml"));
        Settings.load(plugin);
        assertEquals(after, Files.readString(directory.resolve("config.yml")));
    }

    @Test
    void configFromANewerVersionIsRefused() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "config-version: 99\n");
        var error = assertThrows(IllegalStateException.class, () -> Settings.load(plugin));
        assertTrue(error.getMessage().contains("newer"));
        assertEquals("config-version: 99\n", Files.readString(directory.resolve("config.yml")));
    }

    // ---------------------------------------------------------------- settings

    @Test
    void version2ConfigRemovesOldIntegrationSettingsAndKeepsDiscordValues() throws Exception {
        config("integrations:\n  efxcore: false\ndiscord:\n  title: My server\n  include-ip: false\n");
        var settings = Settings.load(plugin);
        assertEquals("My server", settings.webhook().title());
        assertFalse(settings.webhook().includeIp());
        var written = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertFalse(written.isSet("integrations"));
        assertEquals(Settings.CONFIG_VERSION, written.getInt("config-version"));
        try (Stream<Path> backups = Files.list(directory.resolve("backups"))) {
            assertTrue(backups.anyMatch(p -> p.getFileName().toString().startsWith("config-v2-")));
        }
    }


    @Test
    void updateCheckerCanBeDisabledAndRejectsBadIntervals() throws Exception {
        config("update-checker:\n  enabled: false\n");
        assertFalse(Settings.load(plugin).updates().enabled());
        config("update-checker:\n  interval-hours: 0\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin));
    }

    @Test
    void deletingAllPresetsDoesNotResurrectPackagedExamples() throws Exception {
        config("presets: {}\n");
        assertTrue(Settings.load(plugin).presets().isEmpty());
        // An old file without a presets section: the migration must not add the examples either.
        Files.writeString(directory.resolve("config.yml"), "database:\n  type: sqlite\n");
        assertTrue(Settings.load(plugin).presets().isEmpty());
    }

    @Test
    void customPermanentPresetAndAliasAreParsed() throws Exception {
        config("""
                presets:
                  custom:
                    command: custom
                    aliases: [customalias]
                    type: BAN
                    duration: permanent
                    reason: Permanent test
                    permission: efxbans.preset.custom
                """);
        var settings = Settings.load(plugin);

        assertEquals(1, settings.presets().size());
        assertEquals(0, settings.presets().get("custom").duration());
        assertEquals("customalias", settings.topology().get("custom").getFirst());
    }

    @Test
    void turkishPresetCommandsAndAliasesKeepAsciiPermissionDefaults() throws Exception {
        config("""
                presets:
                  kufur:
                    command: küfür
                    aliases: [çığöşü, sustur]
                    type: MUTE
                    duration: 2h
                    reason: Küfür
                  uyarı:
                    type: WARN
                    reason: Uyarı
                """);
        var settings = Settings.load(plugin);

        assertEquals("küfür", settings.presets().get("küfür").command());
        assertEquals("efxbans.preset.kufur", settings.presets().get("küfür").permission());
        assertEquals("çığöşü", settings.topology().get("küfür").getFirst());
        assertEquals("efxbans.preset.uyari", settings.presets().get("uyarı").permission());

        config("presets:\n  bad:\n    command: 'küfür test'\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin));
    }

    @Test
    void reservedCommandsCannotBeClaimedByPresetsAndInvalidYamlFails() throws Exception {
        config("presets:\n  bad:\n    command: ban\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin));
        config("presets: [\n");
        assertThrows(Exception.class, () -> Settings.load(plugin));
    }

    @Test
    void multilineTemplatesKeepUserInputLiteral() throws Exception {
        String text = render(Settings.load(plugin).messages(), "screen.ban", Map.of("reason", "<red>test</red>"));
        assertTrue(text.contains("\n"));
        assertTrue(text.contains("<red>test</red>"));
    }
}
