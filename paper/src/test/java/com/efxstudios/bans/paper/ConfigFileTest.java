package com.efxstudios.bans.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileTest {
    private static final Logger LOGGER = Logger.getLogger("efxBans-test");
    private static final String TEMPLATE = """
            # Do not edit config-version.
            config-version: 3
            # Shown to players.
            greeting: Hello
            limits:
              daily: 5
            # Added in version 3.
            colour: blue
            examples:
              one: 1
            """;

    @TempDir
    Path directory;

    private Path file(String yaml) throws Exception {
        Path file = directory.resolve("test.yml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void stepsRunInOrderAndTheTemplateSuppliesNewKeysWithComments() throws Exception {
        Path file = file("config-version: 1\nwelcome: Hi there\nlimits:\n  per-day: 9\nobsolete: x\n");
        List<ConfigFile.Step> steps = List.of(
                // Deliberately out of order: steps are applied by version, not list position.
                new ConfigFile.Step(2, "rename limits.per-day", c -> ConfigFile.rename(c, "limits.per-day", "limits.daily")),
                new ConfigFile.Step(1, "rename welcome", c -> {
                    ConfigFile.rename(c, "welcome", "greeting");
                    c.set("obsolete", null);
                }));

        YamlConfiguration config = ConfigFile.load(file, TEMPLATE, 0, steps, Set.of("examples"), directory.resolve("backups"), LOGGER);

        assertEquals("Hi there", config.getString("greeting"));
        assertEquals(9, config.getInt("limits.daily"));
        assertEquals("blue", config.getString("colour"));
        assertFalse(config.isSet("obsolete"));
        assertFalse(config.isSet("examples"), "user-owned sections are not filled from the template");
        String written = Files.readString(file);
        assertTrue(written.contains("config-version: 3"));
        assertTrue(written.contains("# Added in version 3."));
        assertTrue(written.contains("# Shown to players."));
        try (Stream<Path> backups = Files.list(directory.resolve("backups"))) {
            Path backup = backups.findFirst().orElseThrow();
            assertTrue(backup.getFileName().toString().startsWith("test-v1-"));
            assertTrue(Files.readString(backup).contains("welcome: Hi there"));
        }
    }

    @Test
    void userOwnedSectionsAreCopiedWhole() throws Exception {
        Path file = file("config-version: 2\nexamples:\n  mine:\n    nested: true\n");
        YamlConfiguration config = ConfigFile.load(file, TEMPLATE, 0, List.of(), Set.of("examples"), directory.resolve("backups"), LOGGER);
        assertTrue(config.getBoolean("examples.mine.nested"));
        assertFalse(config.isSet("examples.one"));
    }

    @Test
    void currentFilesAreNotRewrittenAndNewerFilesAreRefused() throws Exception {
        Path current = file("config-version: 3\ngreeting: Hey\n");
        ConfigFile.load(current, TEMPLATE, 0, List.of(), Set.of(), directory.resolve("backups"), LOGGER);
        assertEquals("config-version: 3\ngreeting: Hey\n", Files.readString(current));
        assertFalse(Files.exists(directory.resolve("backups")));

        Path newer = file("config-version: 4\n");
        assertThrows(IllegalStateException.class, () -> ConfigFile.load(newer, TEMPLATE, 0, List.of(), Set.of(), directory.resolve("backups"), LOGGER));
    }

    @Test
    void helpersDoNotOverwriteExistingValues() throws Exception {
        YamlConfiguration config = ConfigFile.parse("old: 1\nnew: 2\nlist: [a]\n");
        ConfigFile.rename(config, "old", "new");
        ConfigFile.addToList(config, "list", "a", "b");
        assertEquals(2, config.getInt("new"));
        assertFalse(config.isSet("old"));
        assertEquals(List.of("a", "b"), config.getStringList("list"));
    }
}
