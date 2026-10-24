package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingsTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @TempDir
    Path directory;
    BansPlugin plugin;

    @BeforeEach
    void setup() throws Exception {
        plugin = mock(BansPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        for (String file : new String[]{"config.yml", "messages.yml"}) {
            Files.copy(resource(file), directory.resolve(file));
            when(plugin.getResource(file)).thenAnswer(call -> resource(file));
        }
    }

    private java.io.InputStream resource(String file) {
        return getClass().getClassLoader().getResourceAsStream(file);
    }

    private void config(String yaml) throws Exception {
        Files.writeString(directory.resolve("config.yml"), yaml);
    }

    @Test
    void packagedDefaultsLoadAndMultilineTemplatesKeepUserInputLiteral() throws Exception {
        Settings settings = Settings.load(plugin);

        assertEquals(Kind.IPBAN, settings.presets().get("hile").kind());
        String text = PLAIN.serialize(settings.messages().render("screen.ban", Map.of("reason", "<red>test</red>")));
        assertTrue(text.contains("\n"));
        assertTrue(text.contains("<red>test</red>"));
        assertTrue(settings.muteCommands().containsAll(java.util.List.of("teammsg", "tm")));
        assertEquals(new Settings.Updates(true, true, 12), settings.updates());
        assertEquals("/ban [-s] <oyuncu> [süre] <sebep>", settings.messages().text("usages.ban", ""));
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
        config("database:\n  type: sqlite\n");
        assertTrue(Settings.load(plugin).presets().isEmpty());
    }

    @Test
    void customPermanentPresetAndAliasAreParsedWithoutDefaultPresetsBeingAdded() throws Exception {
        config("""
                presets:
                  custom:
                    command: custom
                    aliases: [customalias]
                    type: BAN
                    duration: permanent
                    reason: Kalıcı test
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
    void legacyMessagesMigrateWithBackupWhileCustomMessagesRemain() throws Exception {
        when(plugin.getResource("messages-v1.yml")).thenAnswer(call -> resource("messages-v1.yml"));
        var yaml = new YamlConfiguration();
        yaml.loadFromString(new String(resource("messages-v1.yml").readAllBytes(), StandardCharsets.UTF_8));
        yaml.set("broadcast.ban", "Özel duyuru {player}");
        yaml.save(directory.resolve("messages.yml").toFile());

        var messages = Settings.load(plugin).messages();

        assertEquals("Özel duyuru Alice", PLAIN.serialize(messages.render("broadcast.ban", Map.of("player", "Alice"))));
        assertTrue(PLAIN.serialize(messages.render("broadcast.unmute", Map.of())).contains("susturmasını kaldırdı"));
        assertTrue(PLAIN.serialize(messages.render("inquiry.entry", Map.of())).contains("Veriliş"));
        assertFalse(PLAIN.serialize(messages.render("confirmation.ban", Map.of("id", "99"))).contains("#99"));
        assertTrue(messages.text("update.available", "").contains("{latest}"));
        Path backup = directory.resolve("messages.before-update.yml");
        assertTrue(Files.exists(backup));
        assertTrue(Files.readString(backup).contains("Özel duyuru"));
    }

    @Test
    void reservedCommandsCannotBeClaimedByPresetsAndInvalidYamlFails() throws Exception {
        config("presets:\n  bad:\n    command: ban\n");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(plugin));
        config("presets: [\n");
        assertThrows(Exception.class, () -> Settings.load(plugin));
    }
}
