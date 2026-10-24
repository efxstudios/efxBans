package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Version;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    private static final Version CURRENT = Version.parse("1.1.0");
    private static final String REPOSITORY = "owner/efxBans";

    @Test
    void newerReleaseIsReportedWithAUrlBuiltFromTheRepository() {
        var release = UpdateChecker.newerRelease("""
                {"tag_name": "v1.2.0", "draft": false, "prerelease": false, "html_url": "https://evil.example/"}
                """, CURRENT, REPOSITORY).orElseThrow();
        assertEquals(Version.parse("1.2.0"), release.version());
        assertEquals("https://github.com/owner/efxBans/releases/latest", release.url());
    }

    @Test
    void sameOlderDraftPreReleaseAndMalformedTagsAreIgnored() {
        for (String json : new String[]{
                "{\"tag_name\": \"v1.1.0\"}",
                "{\"tag_name\": \"1.0.9\"}",
                "{\"tag_name\": \"v9.0.0\", \"draft\": true}",
                "{\"tag_name\": \"v9.0.0\", \"prerelease\": true}",
                "{\"tag_name\": \"latest\"}",
                "{\"tag_name\": 5}",
                "{}",
                "[]"})
            assertTrue(UpdateChecker.newerRelease(json, CURRENT, REPOSITORY).isEmpty(), json);
    }

    @Test
    void invalidJsonIsAnError() {
        assertThrows(RuntimeException.class, () -> UpdateChecker.newerRelease("{", CURRENT, REPOSITORY));
    }
}
