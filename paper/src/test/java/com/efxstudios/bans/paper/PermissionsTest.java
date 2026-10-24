package com.efxstudios.bans.paper;

import com.efxstudios.bans.common.Kind;
import com.efxstudios.bans.common.Messages;
import com.efxstudios.bans.common.Times;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionsTest {
    BansPlugin plugin;
    CommandSender sender;
    Permissions permissions;

    static Settings settings(Set<String> console) {
        return new Settings(null, 500, 15000, false, Times.parse("7d"), Map.of("efxbans.limit.senior", Times.parse("30d")), List.of(),
                console, true, Map.of(), new Messages("", Map.of(), "UTC"), null, Settings.Updates.DISABLED);
    }

    @BeforeEach
    void setup() {
        plugin = mock(BansPlugin.class);
        when(plugin.settings()).thenReturn(settings(Set.of("*")));
        sender = mock(CommandSender.class);
        permissions = new Permissions(plugin);
    }

    @Test
    void basePermissionDoesNotGrantEitherDurationMode() {
        when(sender.hasPermission("efxbans.ban")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.BAN, Times.parse("1d")));
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.BAN, 0));

        when(sender.hasPermission("efxbans.ban.temporary")).thenReturn(true);
        assertDoesNotThrow(() -> permissions.issue(sender, Kind.BAN, Times.parse("1d")));
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.BAN, 0));

        when(sender.hasPermission("efxbans.ban.permanent")).thenReturn(true);
        assertDoesNotThrow(() -> permissions.issue(sender, Kind.BAN, 0));
    }

    @Test
    void maximumAndOverrideAreEnforcedWithoutGrantingPunishmentPermissions() {
        when(sender.hasPermission("efxbans.mute")).thenReturn(true);
        when(sender.hasPermission("efxbans.mute.temporary")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.MUTE, Times.parse("8d")));

        when(sender.hasPermission("efxbans.limit.senior")).thenReturn(true);
        assertDoesNotThrow(() -> permissions.issue(sender, Kind.MUTE, Times.parse("30d")));
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.MUTE, Times.parse("31d")));

        when(sender.hasPermission("efxbans.duration.unlimited")).thenReturn(true);
        assertDoesNotThrow(() -> permissions.issue(sender, Kind.MUTE, Times.parse("999d")));
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(sender, Kind.BAN, Times.parse("1d")));
    }

    @Test
    void consoleHasNoSpecialBypassAndAllowListCanRestrictIt() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(console, Kind.BAN, 0));

        when(console.hasPermission(anyString())).thenReturn(true);
        when(plugin.settings()).thenReturn(settings(Set.of("efxbans.ban", "efxbans.ban.temporary")));
        assertThrows(IllegalArgumentException.class, () -> permissions.issue(console, Kind.BAN, 0));
        assertDoesNotThrow(() -> permissions.issue(console, Kind.BAN, Times.parse("1d")));
    }
}
