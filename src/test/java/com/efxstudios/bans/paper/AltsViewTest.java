package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.ActiveCache;
import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Punishment;
import com.efxstudios.bans.common.Store;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AltsViewTest {
    private final Messages messages = new Messages("", Map.of("alts-entry", "• {player}", "lists.hover", "{staff}: {reason}"), "UTC");
    private final Store.Profile profile = new Store.Profile(UUID.randomUUID(), "Related", "127.0.0.1", 0);

    private Punishment ban(Kind kind, UUID target, String state, long expiry) {
        return new Punishment(42, kind, target, "Related", profile.ip(), "CONSOLE", "Staff", "Reason", 1, expiry,
                state, 0, null, null, null, false);
    }

    private TextComponent name(Component line) {
        if (line instanceof TextComponent text && text.content().equals(profile.name())) return text;
        return line.children().stream().map(this::name).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    @Test
    void onlineAndOfflineAccountsHaveDifferentLightColorsWithoutLinks() {
        var online = name(AltsView.entry(messages, profile, true, Optional.empty(), true));
        var offline = name(AltsView.entry(messages, profile, false, Optional.empty(), true));
        assertEquals(AltsView.ONLINE, online.color());
        assertEquals(AltsView.OFFLINE, offline.color());
        assertNull(online.clickEvent());
        assertNull(offline.clickEvent());
    }

    @Test
    void activeBanOverridesOnlineColorAndLinksToItsExactLookup() {
        var line = AltsView.entry(messages, profile, true, Optional.of(ban(Kind.BAN, profile.uuid(), "ACTIVE", 0)), true);
        var name = name(line);
        assertEquals(AltsView.BANNED, name.color());
        assertEquals(ClickEvent.runCommand("/efxbans:lookup #42"), name.clickEvent());
        assertNotNull(name.hoverEvent());
        assertEquals("• Related", PlainTextComponentSerializer.plainText().serialize(line));
    }

    @Test
    void banIsRedButHasNoLinkWithoutLookupPermission() {
        var name = name(AltsView.entry(messages, profile, false, Optional.of(ban(Kind.BAN, profile.uuid(), "ACTIVE", 0)), false));
        assertEquals(AltsView.BANNED, name.color());
        assertNull(name.clickEvent());
        assertNull(name.hoverEvent());
    }

    @Test
    void addressBanMatchesRelatedAccountButRemovedExpiredAndMutesDoNot() {
        ActiveCache cache = new ActiveCache();
        Set<Kind> kinds = Set.of(Kind.BAN, Kind.IPBAN);
        cache.replace(List.of(ban(Kind.IPBAN, null, "ACTIVE", 0)));
        assertEquals(AltsView.BANNED, name(AltsView.entry(messages, profile, false,
                cache.find(profile.uuid(), profile.ip(), kinds), true)).color());
        for (Punishment inactive : List.of(ban(Kind.BAN, profile.uuid(), "REMOVED", 0),
                ban(Kind.BAN, profile.uuid(), "ACTIVE", 2), ban(Kind.MUTE, profile.uuid(), "ACTIVE", 0))) {
            cache.replace(List.of(inactive));
            var name = name(AltsView.entry(messages, profile, false, cache.find(profile.uuid(), profile.ip(), kinds), true));
            assertEquals(AltsView.OFFLINE, name.color());
            assertNull(name.clickEvent());
        }
    }

    @Test
    void nameCannotInjectMiniMessageOrACommand() {
        var malicious = new Store.Profile(profile.uuid(), "<click:run_command:'/op Attacker'>Name</click>", null, 0);
        var line = AltsView.entry(messages, malicious, false, Optional.empty(), true);
        assertTrue(PlainTextComponentSerializer.plainText().serialize(line).contains(malicious.name()));
        assertNull(line.clickEvent());
    }
}
