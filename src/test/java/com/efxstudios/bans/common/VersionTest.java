package com.efxstudios.bans.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTest {
    @Test
    void parsesPlainPrefixedAndPreReleaseVersions() {
        assertEquals(new Version(1, 2, 3, List.of()), Version.parse("1.2.3"));
        assertEquals(new Version(1, 2, 3, List.of()), Version.parse("v1.2.3"));
        assertEquals(new Version(2, 0, 0, List.of("beta", "1")), Version.parse("2.0.0-beta.1+build.7"));
        assertEquals("2.0.0-beta.1", Version.parse("2.0.0-beta.1+build.7").toString());
        assertTrue(Version.parse("1.0.0-rc.1").isPreRelease());
    }

    @Test
    void rejectsAnythingThatIsNotSemver() {
        for (String text : new String[]{"1", "1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-01", "latest", "", "99999999999.0.0"})
            assertThrows(IllegalArgumentException.class, () -> Version.parse(text), text);
    }

    @Test
    void followsSemverPrecedence() {
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
                "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0");
        for (int i = 1; i < ordered.size(); i++) {
            Version lower = Version.parse(ordered.get(i - 1));
            Version higher = Version.parse(ordered.get(i));
            assertTrue(higher.isNewerThan(lower), higher + " > " + lower);
            assertFalse(lower.isNewerThan(higher), lower + " < " + higher);
        }
        assertEquals(0, Version.parse("1.0.0+a").compareTo(Version.parse("1.0.0+b")));
    }
}
