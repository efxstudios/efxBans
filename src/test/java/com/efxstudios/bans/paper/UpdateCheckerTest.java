package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UpdateCheckerTest {
    private static final Version CURRENT = Version.parse("1.1.0");
    private static String release(String version, String type, String status, String game, String loader) {
        return "{\"version_number\":\"" + version + "\",\"version_type\":\"" + type + "\",\"status\":\"" + status
                + "\",\"game_versions\":[\"" + game + "\"],\"loaders\":[\"" + loader + "\"],\"url\":\"https://evil.example/\"}";
    }

    @Test
    void newestCompatibleStableReleaseLinksOnlyToTheModrinthProject() {
        String json = "[" + release("1.2.0", "release", "listed", "1.21.11", "paper") + ","
                + release("1.4.0", "release", "listed", "1.21.11", "paper") + ","
                + release("1.3.0", "release", "listed", "1.21.11", "paper") + "]";
        var found = UpdateChecker.newerRelease(json, CURRENT, "1.21.11", "paper").orElseThrow();
        assertEquals(Version.parse("1.4.0"), found.version());
        assertEquals("https://modrinth.com/project/efxbans/", found.url());
    }

    @Test
    void sameOlderPrereleaseDraftAndUnlistedVersionsAreIgnored() {
        for (String json : new String[]{
                release("1.1.0", "release", "listed", "1.21.11", "paper"),
                release("1.0.9", "release", "listed", "1.21.11", "paper"),
                release("9.0.0", "beta", "listed", "1.21.11", "paper"),
                release("9.0.0", "alpha", "listed", "1.21.11", "paper"),
                release("9.0.0-beta.1", "release", "listed", "1.21.11", "paper"),
                release("9.0.0", "release", "draft", "1.21.11", "paper"),
                release("9.0.0", "release", "unlisted", "1.21.11", "paper"),
                release("9.0.0", "release", "scheduled", "1.21.11", "paper"),
                release("9.0.0", "release", "archived", "1.21.11", "paper"),
                release("latest", "release", "listed", "1.21.11", "paper")})
            assertTrue(UpdateChecker.newerRelease("[" + json + "]", CURRENT, "1.21.11", "paper").isEmpty(), json);
    }

    @Test
    void minecraftVersionAndActualServerLoaderMustBothMatch() {
        String onlyPaper = "[" + release("1.2.0", "release", "listed", "1.21.11", "paper") + "]";
        assertTrue(UpdateChecker.newerRelease(onlyPaper, CURRENT, "1.20.6", "paper").isEmpty());
        assertTrue(UpdateChecker.newerRelease(onlyPaper, CURRENT, "1.21.11", "folia").isEmpty());
        String both = onlyPaper.replace("[\"paper\"]", "[\"paper\",\"folia\"]");
        assertTrue(UpdateChecker.newerRelease(both, CURRENT, "1.21.11", "folia").isPresent());
    }

    @Test
    void missingOrIncorrectlyTypedMetadataIsIgnored() {
        for (String json : new String[]{"{}", "[]", "[null,2,{},\"bad\"]", "[{\"version_number\":5}]"})
            assertTrue(UpdateChecker.newerRelease(json, CURRENT, "1.21.11", "paper").isEmpty(), json);
    }

    @Test
    void invalidJsonIsAnError() {
        assertThrows(RuntimeException.class, () -> UpdateChecker.newerRelease("{", CURRENT, "1.21.11", "paper"));
    }
}
